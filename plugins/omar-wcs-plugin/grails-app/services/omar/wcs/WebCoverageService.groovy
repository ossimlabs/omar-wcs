package omar.wcs

import geoscript.workspace.PostGIS
import geoscript.workspace.Workspace
import grails.gorm.transactions.Transactional
import groovy.json.JsonBuilder
import groovy.xml.StreamingMarkupBuilder

import geoscript.geom.Bounds
import geoscript.render.Map as GeoScriptMap
import omar.geoscript.LayerInfo

import omar.core.DateUtil

@Transactional(readOnly = true)
class WebCoverageService
{
  def grailsLinkGenerator
  def grailsApplication
  def geoscriptService

  static final supportedFormats = [
      "GeoTIFF", //"GIF", "PNG", "TIFF"
  ]

  static final requestResponseCRSs = [
      "EPSG:4326"
  ]

  static final supportedInterpolations = [
      "nearest neighbor"
  ]

  static final defaultInterpolation = "nearest neighbor"

  def getCapabilities(GetCapabilitiesRequest wcsParams)
  {
    def requestType = "GET"
    def requestMethod = "GetCapabilities"
    Date startTime = new Date()
    def responseTime
    def requestInfoLog
    def httpStatus = 200

    def contentType
    def buffer

    try
    {
      def schemaLocation = grailsLinkGenerator.link( absolute: true, uri: '/schemas/wcs/1.0.0/wcsCapabilities.xsd' )
      def wcsServiceAddress = grailsLinkGenerator.link( absolute: true, uri: '/wcs' )

      def x = {
        mkp.xmlDeclaration()
        mkp.declareNamespace(
            gml: "http://www.opengis.net/gml",
            ogc: "http://www.opengis.net/ogc",
            ows: "http://www.opengis.net/ows/1.1",
            wcs: "http://www.opengis.net/wcs",
            xlink: "http://www.w3.org/1999/xlink",
            xsi: "http://www.w3.org/2001/XMLSchema-instance"
        )
        wcs.WCS_Capabilities(
            version: "1.0.0",
            'xsi:schemaLocation': "http://www.opengis.net/wcs ${schemaLocation}"
        ) {
          wcs.Service {
            wcs.metadataLink( about: "http://o2.ossim.org",
                metadataType: "other", 'xlink:type': "simple" )
            wcs.description( "This server implements the WCS specification 1.0.0" )
            wcs.name( "WCS" )
            wcs.label( "Web Coverage Service" )
            wcs.keywords {
              wcs.keyword( "WCS" )
              wcs.keyword( "WMS" )
              wcs.keyword( "OMAR" )
            }
            wcs.responsibleParty {
              wcs.individualName()
              wcs.organisationName()
              wcs.positionName()
              wcs.contactInfo {
                wcs.phone()
                wcs.address {
                  wcs.city()
                  wcs.country()
                  wcs.electronicMailAddress()
                }
              }
            }
            wcs.fees( "NONE" )
            wcs.accessConstraints( "NONE" )
          }
          wcs.Capability {
            wcs.Request {
              wcs.GetCapabilities {
                wcs.DCPType {
                  wcs.HTTP {
                    wcs.Get {
                      wcs.OnlineResource( 'xlink:href': "${wcsServiceAddress}" )
                    }
                  }
                }
/*
              wcs.DCPType {
                wcs.HTTP {
                  wcs.Post {
                    wcs.OnlineResource( 'xlink:href': wcsServiceAddress )
                  }
                }
              }
*/
              }
              wcs.DescribeCoverage {
                wcs.DCPType {
                  wcs.HTTP {
                    wcs.Get {
                      wcs.OnlineResource( 'xlink:href': "${wcsServiceAddress}" )
                    }
                  }
                }
/*
              wcs.DCPType {
                wcs.HTTP {
                  wcs.Post {
                    wcs.OnlineResource( 'xlink:href': wcsServiceAddress )
                  }
                }
              }
*/
              }
              wcs.GetCoverage {
                wcs.DCPType {
                  wcs.HTTP {
                    wcs.Get {
                      wcs.OnlineResource( 'xlink:href': "${wcsServiceAddress}" )
                    }
                  }
                }
/*
              wcs.DCPType {
                wcs.HTTP {
                  wcs.Post {
                    wcs.OnlineResource( 'xlink:href': wcsServiceAddress )
                  }
                }
              }
*/
              }
            }
            wcs.Exception {
              wcs.Format( 'application/vnd.ogc.se_xml' )
            }
          }

          def layers

          try
          {
            layers = getLayers( wcsParams )
          }
          catch ( e )
          {
            contentType = 'application/vnd.ogc.se_xml'
            buffer = createErrorMessage( e )
            httpStatus = 400
            Date endTime = new Date()
            responseTime = Math.abs(startTime.getTime() - endTime.getTime())

            requestInfoLog = new JsonBuilder(timestamp: DateUtil.formatUTC(startTime), requestType: requestType,
                    requestMethod: requestMethod, contentType: contentType, filter: wcsParams?.filter, coverage: wcsParams?.coverage,
                    httpStatus: httpStatus, endTime: DateUtil.formatUTC(endTime),
                    responseTime: responseTime, responseSize: buffer.toString().bytes.length)

            log.info requestInfoLog.toString()
            return [contentType: contentType, buffer: buffer]
          }

          wcs.ContentMetadata {
            layers?.each { layer ->
              wcs.CoverageOfferingBrief {
                wcs.description( layer.description )
                wcs.name( layer.name )
                wcs.label( layer.label )
                wcs.lonLatEnvelope( srsName: layer.bounds.proj ) {
                  gml.pos( "${layer.bounds.minX} ${layer.bounds.minY}" )
                  gml.pos( "${layer.bounds.maxX} ${layer.bounds.maxY}" )
                }
                wcs.keywords {
                  layer.keywords.each {
                    wcs.keyword( it )
                  }
                }
              }
            }
          }
        }
      }

      def xml = new StreamingMarkupBuilder( encoding: 'utf-8' ).bind( x )

      contentType = 'application/xml'
      buffer = xml

    }
    catch ( e )
    {
      contentType = 'application/vnd.ogc.se_xml'
      buffer = createErrorMessage( e )
      httpStatus = 400
    }


    Date endTime = new Date()
    responseTime = Math.abs(startTime.getTime() - endTime.getTime())

    requestInfoLog = new JsonBuilder(timestamp: DateUtil.formatUTC(startTime), requestType: requestType,
            requestMethod: requestMethod, contentType: contentType, filter: wcsParams?.filter, coverage: wcsParams?.coverage,
            httpStatus: httpStatus, endTime: DateUtil.formatUTC(endTime),
            responseTime: responseTime, responseSize: buffer.toString().bytes.length)

    log.info requestInfoLog.toString()

    [contentType: contentType, buffer: buffer]
  }

  private def getLayers(def wcsParams) throws Exception
  {
    def coverages = wcsParams?.coverage?.split( ',' )*.split( /[:\.]/ )
    def images = []

    coverages?.each { coverage ->
//      println coverage
      def prefix, layerName, id

      if ( coverage?.size() == 3 )
      {
        (prefix, layerName, id) = coverage
      }
      else if ( coverage?.size() == 2 )
      {
        (prefix, layerName) = coverage
      }

      if ( layerName == null || prefix == null )
      {
        throw new Exception( "Unknown coverage:  ${prefix}:${layerName}" )
      }

      def layerInfo = LayerInfo.where {
        name == layerName && workspaceInfo.namespaceInfo.prefix == prefix
      }.get()

      Workspace.withWorkspace( geoscriptService.getWorkspace( layerInfo.workspaceInfo.workspaceParams ) ) { workspace ->
        def layer = workspace[layerName]

        if ( id )
        {
          def f = "in (${id})"
          def image = layer?.getFeatures( filter: f )
          if ( image )
          {
            images << convertImage( prefix, image )
          }
        }
        else if ( wcsParams.filter )
        {
          layer?.eachFeature( filter: wcsParams.filter ) { images << convertImage( prefix, it ) }
        }
      }
    }

    images
  }

  private def convertImage(def prefix, def image)
  {

    def bounds = image.ground_geom.bounds
    def title = image.title ?: image.image_id ?: ( image.filename as File )?.name

    def metadata = [
        label: title,
        description: image.description,
        name: "${prefix}:${image.id}",
        bounds: [
            //srsName: "urn:ogc:def:crs:OGC:1.3:CRS84",
            proj: "EPSG:4326",
            minX: bounds.minX, minY: bounds.minY, maxX: bounds.maxX, maxY: bounds.maxY
        ],
        keywords: ["WCS"],
        width: image.width, height: image.height,
        numBands: image.number_of_bands,
        filename: image.filename,
        entry: image.entry?.toInteger()
    ]

//    println metadata
    metadata
  }

  def describeCoverage(DescribeCoverageRequest wcsParams)
  {
    def requestType = "GET"
    def requestMethod = "DescribeCoverage"
    Date startTime = new Date()
    def responseTime
    def requestInfoLog
    def httpStatus = 200

    def contentType
    def buffer

    try
    {
      def schemaLocation = grailsLinkGenerator.link( absolute: true, uri: '/schemas/wcs/1.0.0/describeCoverage.xsd' )

      def x = {
        mkp.xmlDeclaration()
        mkp.declareNamespace(
            gml: "http://www.opengis.net/gml",
            ogc: "http://www.opengis.net/ogc",
            ows: "http://www.opengis.net/ows/1.1",
            wcs: "http://www.opengis.net/wcs",
            xlink: "http://www.w3.org/1999/xlink",
            xsi: "http://www.w3.org/2001/XMLSchema-instance"
        )

        def layers = getLayers( wcsParams )

        wcs.CoverageDescription( version: "1.0.0",
            'xsi:schemaLocation': "http://www.opengis.net/wcs ${schemaLocation}" ) {
          layers.each { layer ->

            wcs.CoverageOffering {
              wcs.description( layer.description )
              wcs.name( layer.name )
              wcs.label( layer.label )

              wcs.lonLatEnvelope( srsName: layer.bounds.proj ) {
                gml.pos( "${layer.bounds.minX} ${layer.bounds.minY}" )
                gml.pos( "${layer.bounds.maxX} ${layer.bounds.maxY}" )
              }

              wcs.keywords {
                layer.keywords.each { wcs.keyword( it ) }
              }

              wcs.domainSet {
                wcs.spatialDomain {
                  gml.Envelope( srsName: layer.bounds.proj ) {
                    gml.pos( "${layer.bounds.minX} ${layer.bounds.minY}" )
                    gml.pos( "${layer.bounds.maxX} ${layer.bounds.maxY}" )
                  }
//              gml.RectifiedGrid( dimension: "2", srsName: layer.bbox.srsName ) {
//                gml.limits {
//                  gml.GridEnvelope {
//                    gml.low( "0 0" )
//                    gml.high( "${layer.width} ${layer.height}" )
//                  }
//                }
//                gml.axisName( "x" )
//                gml.axisName( "y" )
//                gml.origin {
//                  gml.pos( "-130.81666154628687 54.08616613712375" )
//                }
//                gml.offsetVector( "0.07003690742624616 0.0" )
//                gml.offsetVector( "0.0 -0.05586772575250837" )
//              }
                }
              }
              wcs.rangeSet {
                wcs.RangeSet {
                  wcs.name( layer.name )
                  wcs.label( layer.description )
                  wcs.axisDescription {
                    wcs.AxisDescription {
                      wcs.name( "Band" )
                      wcs.label( "Band" )
                      wcs.values {
                        wcs.interval {
                          wcs.min( 0 )
                          wcs.max( layer.numBands - 1 )
                        }
                      }
                    }
                  }
                }
              }
              wcs.supportedCRSs {
                requestResponseCRSs.each { wcs.requestResponseCRSs( it ) }
              }
              wcs.supportedFormats( /*nativeFormat: "WorldImage"*/ ) {
                supportedFormats.each { wcs.formats( it ) }
              }
              wcs.supportedInterpolations( default: defaultInterpolation ) {
                supportedInterpolations.each { wcs.interpolationMethod( it ) }
              }
            }
          }
        }
      }

      def xml = new StreamingMarkupBuilder( encoding: 'utf-8' ).bind( x )

      contentType = 'application/xml'
      buffer = xml
    }
    catch ( e )
    {
      contentType = 'application/vnd.ogc.se_xml'
      buffer = createErrorMessage( e )
      httpStatus = 400
    }

    Date endTime = new Date()
    responseTime = Math.abs(startTime.getTime() - endTime.getTime())

    requestInfoLog = new JsonBuilder(timestamp: DateUtil.formatUTC(startTime), requestType: requestType,
            requestMethod: requestMethod, contentType: contentType, filter: wcsParams?.filter, coverage: wcsParams?.coverage,
            httpStatus: httpStatus, endTime: DateUtil.formatUTC(endTime),
            responseTime: responseTime, responseSize: buffer.toString().bytes.length)

    log.info requestInfoLog.toString()

    [contentType: contentType, buffer: buffer]
  }


def createWorkspace()
{
    def config = grailsApplication.config
    def url = config.dataSource.url
    def username = config.dataSource.username
    def password = config.dataSource.password

    def jdbcURL = url =~ /^jdbc:postgresql:(\/\/((.+)(:(\d+)))\/)?(.+)$/  

    new PostGIS( jdbcURL[0][6], 
      user: username ?: 'postgres',
      password: password ?: 'postgres',
      host: jdbcURL[0][3] ?:'localhost',
      port: jdbcURL[0][5] ?: '5432'
    )
}



def getImageInfo( def typeName, def filter ) 
{
  // println "${typeName} ${filter}"

    def imageInfo
    
    Workspace.withWorkspace( createWorkspace()) { w ->
        def x =  typeName?.split(':')
        def prefix, layerName

        if ( x?.size() == 2 )
        {
          prefix = x[0]
          layerName = x[1]
        }
        else
        {
          layerName = typeName
        }

        def rasterEntry = w[layerName].getFeatures(
            filter: filter,
            max: 1
        )

        if (rasterEntry?.size() > 0)
        {
          imageInfo = rasterEntry.first().attributes
        }
    }
    
    imageInfo
}

def getCoverage( GetCoverageRequest wcsParams ) 
{
  // println wcsParams

    def ext = [
        'image/png': '.png',
        'image/jpeg': '.jpg',
        'image/tiff': '.tif',
        'image/nitf': '.ntf'
    ]
    
    def writers = [
        'image/png': 'ossim_png',
        'image/jpeg': 'jpeg',
        'image/tiff': 'tiff_tiled_band_separate',
        'image/nitf': 'ossim_kakadu_nitf_j2k'
    ]    



    def tempDir = new File( grailsApplication.config.omar.wcs.tempDir ?: "${System.getProperty('user.dir')}/tmp" )
    
    if ( ! tempDir.exists() ) {
      tempDir.mkdirs()
    }
    
    def outputFile = File.createTempFile( 'chipper-', ext[wcsParams.format], tempDir  )
    // println  outputFile   
    def viewBbox = new Bounds( *( wcsParams?.bbox?.split( ',' )*.toDouble() ), wcsParams.crs )
    // println  viewBbox   

    def imageInfo = getImageInfo( wcsParams.coverage, wcsParams.filter ) 

    // def imageInfo = getLayers(wcsParams)

    if ( ! imageInfo )
    {
      return [ contentType: "text/plain", text: "ServiceExceptionReport: No image matching filter found!" ]
    }

    // println imageInfo
    
    def cmd = [
        'ossim-chipper',
        '--op', 'ortho',
        '--bands', 'default',
        '--cut-wms-bbox', [ 'minX', 'minY', 'maxX', 'maxY' ].collect { viewBbox[it] }.join( ',' ),
        '--cut-width', wcsParams.width,
        '--cut-height', wcsParams.height,
        '--srs', viewBbox.proj.srs,
        '--writer', writers[wcsParams.format],
        '--writer-prop', 'create_external_geometry=false',
        '--histogram-op', 'auto-minmax',
        imageInfo.filename,
        '--entry', imageInfo.entry_id,
        outputFile.absolutePath,
    ]
    
    if ( wcsParams.format == 'image/png' && imageInfo.bit_depth > 8 )
    {
        cmd << '--scale-to-8-bit'
    }
    
    if ( wcsParams.width >= imageInfo.width && wcsParams.height >= imageInfo.height )
    {                 
        cmd << '-K'
        cmd << "tile_size='1024 1024'"
    }        
    
    println cmd.join( ' ' )
    
    def start = System.currentTimeMillis()
    def proc = cmd.execute()
    
    proc.consumeProcessOutput()
    
    int exitCode = proc.waitFor()
    def stop = System.currentTimeMillis()
    
    println "${ stop - start }"
    
    if ( !exitCode )
    {
        [ contentType: wcsParams.format, file: outputFile ]
    }
    else
    {
        [ contentType: wcsParams.format, text: "ERROR!" ]
    }
}



  def getCoverageOLD(GetCoverageRequest wcsParams)
  {
    def requestType = "GET"
    def requestMethod = "GetCoverage"
    Date startTime = new Date()
    def responseTime
    def requestInfoLog
    def httpStatus = 200

    def contentType
    def buffer

    try
    {
      def coverageLayers = getLayers( wcsParams )?.collect { coverage ->
        new ChipperLayer( coverage.filename as File, coverage.entry )
      }

      def viewBbox = new Bounds( *( wcsParams?.bbox?.split( ',' )*.toDouble() ), wcsParams.crs )
      def coverageBbox = coverageLayers?.first()?.bbox

      coverageLayers?.each { coverageBbox = coverageBbox.expand( it.bbox ) }

      def bbox = viewBbox.intersection( coverageBbox )

      def map = new GeoScriptMap(
          fixAspectRatio: false,
          type: wcsParams?.format?.toLowerCase(),
          width: wcsParams?.width,
          height: wcsParams?.height,
          proj: bbox.proj,
          bounds: bbox,
          layers: coverageLayers
      )

      def ostream = new ByteArrayOutputStream()

      map.renderers['geotiff'] = new GeoTIFF()
      map?.render( ostream )
      map?.close()

      contentType = 'image/tiff'
      buffer = ostream.toByteArray()
    }
    catch ( e )
    {
      contentType = 'application/vnd.ogc.se_xml'
      buffer = createErrorMessage( e )?.bytes
      httpStatus = 400
    }

    Date endTime = new Date()
    responseTime = Math.abs(startTime.getTime() - endTime.getTime())

    requestInfoLog = new JsonBuilder(timestamp: DateUtil.formatUTC(startTime), requestType: requestType,
            requestMethod: requestMethod, contentType: contentType, filter: wcsParams?.filter, coverage: wcsParams?.coverage,
            width: wcsParams?.width, height: wcsParams?.height, bbox: wcsParams?.bbox, httpStatus: httpStatus, endTime: DateUtil.formatUTC(endTime),
            responseTime: responseTime, responseSize: buffer.toString().bytes.length)

    log.info requestInfoLog.toString()

    [contentType: contentType, buffer: buffer]
  }

  private createErrorMessage(Exception e)
  {
    def schemaLocation = grailsLinkGenerator.link( absolute: true, uri: '/schemas/wcs/1.0.0/OGC-exception.xsd' )

    def x = {
      mkp.xmlDeclaration()
      mkp.declareNamespace(
          xsi: "http://www.w3.org/2001/XMLSchema-instance"
      )
      ServiceExceptionReport(
          version: "1.2.0",
          xmlns: "http://www.opengis.net/ogc",
          'xsi:schemaLocation': schemaLocation
      ) {
        ServiceException( e.message )
      }
    }

    def xml = new StreamingMarkupBuilder( encoding: 'utf-8' ).bind( x )

    xml.toString()
  }
}
