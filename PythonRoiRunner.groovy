// Here add your pre-configuration steps and initialize an instance of this script

// The RoiRunner is defined below and nothing should be changed below, normally
class PythonRoiRunner {
    File python_executable
    File python_script_file
    File image_directory
    File current_project_temp_dir
    int tile_size = 2048
    int overlap = 30
    int downsample = 1
    double cell_thickness = 0.0
    ImmutableDimension dims
        
    // Parameters that are unique to each python script as key value pairs where the key is the command line argument name
    def parameters = [:]
    
    public PythonRoiRunner( String virtualenv_directory_string, String python_script_file_string) {
        this.python_executable = new File( virtualenv_directory_string, "Scripts/python" )
        this.python_script_file = new File( python_script_file_string )
        logger.info("Script: {}", python_script_file)
        this.current_project_temp_dir = new File( Projects.getBaseDirectory( getProject() ), python_script_file.getName()+"_tmp" )
        current_project_temp_dir.mkdirs()
        
        // This is needed when requesting tiles
        this.dims = new ImmutableDimension( tile_size, tile_size )
    }
    
    PythonRoiRunner setOverlap(int overlap) {
        this.overlap = overlap
        return this
    }
    
    PythonRoiRunner setTileSize(int tile_size) {
        this.tile_size = tile_size
        return this
    }
    
    PythonRoiRunner setParameters( Map<String, String> parameters ) {
        this.parameters = parameters
        return this
    }
    
    PythonRoiRunner makeCells( double cell_thickness ) {
        this.cell_thickness = cell_thickness
        return this
    }    
    
    PythonRoiRunner setDownsample( int downsample ) {
        this.downsample = downsample
        return this
    }
    
    
    // This processes the given region, at the desired downsampling and with the chosen code for preprocessing
    // Code should take an ImagePlus as input argument and return the same ImagePlus
    void processRegion( PathObject region, Closure preprocess ) {
    
        // Prepare a temporary folder to work
        def temp_folder = Files.createTempDirectory( this.current_project_temp_dir.toPath(), "Temp" );
        logger.info( "Storing Temporary images in {}", this.current_project_temp_dir )
        
        // Use this as the data folder
        def data_folder = temp_folder.toFile()

        // We expect the Python script to store store the resulting RoiSets in 'rois'
        def roiset_folder = new File( data_folder, 'rois' )

         // Make tiles as needed 
        def regions = RoiTools.computeTiledROIs( region.getROI(), this.dims, this.dims, true, this.overlap ).collect {
            return new PathAnnotationObject( it )
        }

        // Show the regions that are being processed
        region.addPathObjects( regions )
        fireHierarchyUpdate()

        // Compute global min-max normalization values to use here on the full region. Downsample if the image is tiled
        def downsample_corr = 1.0
        if ( regions.size() > 1 ) {
            downsample_corr = 4.0
        }
        
        logger.info( "Computing Min and Max values for data normalization on {}x downsampled data", this.downsample * downsample_corr as int )

        // Compute on the whole image        
        def full_image = GUIUtils.getImagePlus( region, this.downsample * downsample_corr as int, false, false )
        if( preprocess != null ) 
            full_image = preprocess( full_image )

        def min_max = getQuantileMinMax( full_image, 1.0, 99.8 )

        logger.info( "Normalization: Min={}, Max={}", min_max["min"], min_max["max"] )

        full_image.close()

        // Save all the regions to the data_folder, and keep their information for when we pick up the Rois later
        def region_metadata = regions.withIndex().collect { r, idx ->

            ImagePlus imp = GUIUtils.getImagePlus( r, this.downsample, false, false )            

            def cal = imp.getCalibration().clone()

            if( preprocess != null ) 
                imp = preprocess( imp )
                imp.setCalibration(cal)
                
            File image_file = new File( data_folder, "region" + IJ.pad( idx, 3 ) )

            // Save the image
            IJ.saveAsTiff( imp, image_file.getAbsolutePath() )
            logger.info( "Saved Image {}.tif", image_file.getName() )

            imp.close()

            // Return a List with the image name, the region and the calibration ( To get the ROIs properly later )
            return [name: image_file.getName(), region: r, calibration: cal]
        }

        // Call the script, this is the magic line
        runScript( data_folder, min_max )

        // After this step, there should be ROIs in the 'rois' folder
        // Get ROI Set(s) and import
        def rm = RoiManager.getRoiManager()


        def all_detections = []
        
        region_metadata.each { meta ->
            def current_name = meta.name
            def current_region = meta.region
            def cal = meta.calibration

            // The RoiSet should have the same name as the image, with 'rois.zip' appended to it
            def roi_file = new File( roiset_folder, current_name + ".tif_rois.zip" )

            if ( roi_file.exists() ) {
                logger.info( "Image {}.tif had a RoiSet", current_name )

                rm.reset()
                rm.runCommand( "Open", roi_file.getAbsolutePath() )

                def rois = rm.getRoisAsArray() as List

                def detections = rois.collect {
                    def roi = IJTools.convertToROI( it, cal, downsample, null )
                    if ( current_region.getROI().contains( roi.getCentroidX(), roi.getCentroidY() ) )
                        return new PathDetectionObject( roi )
                    return[]
                }.flatten()

                if ( this.cell_thickness > 0 ) {
                    logger.info( "Creating Cells from {} Detections...", detections.size() )
                    def cell_detections = PathUtils.createCellObjects( current_region, detections, this.cell_radius, downsample )
                    all_detections.addAll( cell_detections )

                } else {
                    logger.info( "Adding {} detections", detections.size() )
                    all_detections.addAll( detections )
                }
            }
        }
        
        if ( regions.size() > 1 ) {
            logger.info( "Removing overlapping objects" )
            // Find the detections that may need to be removed

            def overlaping_detections = getOverlapingDetetections( regions, all_detections )

            // Remove overlap and add the ones to keep again after


            // Do some filtering to avoid issues where there is overlap
            def removable_regions = getRoisToDetleteByOverlap( overlaping_detections, 40 )

            // Remove
            all_detections.removeAll( removable_regions )
        }
        

        region.addPathObjects( all_detections )

        region.removePathObjects( regions )

        fireHierarchyUpdate()

        temp_folder.toFile().deleteDir()

        logger.info( "Done" )

    }

    def getOverlapingDetetections( def regions, def all_detections ) {

        // Get all overlap regions
        def overlap_regions = []
        regions.each { r1 ->
            regions.each { r2 ->
                if ( r1 != r2 ) {
                    // check overlap
                    def merge = RoiTools.combineROIs( r1.getROI(), r2.getROI(), RoiTools.CombineOp.INTERSECT )
                    if ( !merge.isEmpty() ) {
                        // Make into an annotation that represents the overlap
                        overlap_regions.add( new PathAnnotationObject( merge ) )
                    }
                }
            }
        }

        // Combine all now
        setSelectedObject( null )
        mergeAnnotations( overlap_regions )
        def merged = getSelectedObject()
        if( merged != null ) removeObject( merged, false )
        setSelectedObject( null )
        
        // Find all annotations that are touching somehow this region, avoid shapes as they are slow
        def overlap_detections = all_detections.findAll {
            def roi = it.getROI()

            def x1 = roi.getCentroidX()
            def y1 = roi.getCentroidY()

            def x2 = roi.getBoundsX()
            def y2 = roi.getBoundsY()

            def x3 = roi.getBoundsX() + roi.getBoundsWidth()
            def y3 = roi.getBoundsY()

            def x4 = roi.getBoundsX()
            def y4 = roi.getBoundsY() + roi.getBoundsHeight()


            def x5 = roi.getBoundsX() + roi.getBoundsWidth()
            def y5 = roi.getBoundsY() + roi.getBoundsHeight()

            return merged.getROI().contains( x1, y1 ) || merged.getROI().contains( x2, y2 ) || merged.getROI().contains( x3, y3 ) || merged.getROI().contains( x4, y4 ) || merged.getROI().contains( x5, y5 )
        }

        // From here get a hashmap of the regions when their bounding boxes match
        def temp_overlap_detections = overlap_detections.clone()

        def detections_to_check = [:]
        overlap_detections.each { det ->
            temp_overlap_detections.remove( det )
            def det_candidates = temp_overlap_detections.collect { det1 ->
                if ( hasBBOverlap( det, det1 ) ) {
                    return det1
                }
                return []
            }.flatten()

            detections_to_check.put( det, det_candidates )
        }

        logger.info( "There are {} detections that potentially overlap", detections_to_check.size() )
        logger.info( "{}", detections_to_check )
        return detections_to_check
    }

    boolean hasBBOverlap( def po1, def po2 ) {
        ROI r1 = po1.getROI()

        double r1_left = r1.getBoundsX()
        double r1_top = r1.getBoundsY()
        double r1_right = r1.getBoundsWidth() + r1_left
        double r1_bottom = r1.getBoundsHeight() + r1_top

        ROI r2 = po2.getROI()

        double r2_left = r2.getBoundsX()
        double r2_top = r2.getBoundsY()
        double r2_right = r2.getBoundsWidth() + r2_left
        double r2_bottom = r2.getBoundsHeight() + r2_top

        return !( r2_left > r1_right
                || r2_right < r1_left
                || r2_top > r1_bottom
                || r2_bottom < r1_top )
    }

    // "percentOverlap" : compare the percent overlap of each roi.Areas,
    //  and delete the roi with the largent percentage (most probably included within the other).
    def getRoisToDetleteByOverlap( def roiMap, def percent_overlap_lim ) {

        logger.info( "Overlap Filter: Overlap Limit {}%", percent_overlap_lim )

        def roisToDelete = []

        roiMap.each { rA, candidates ->
            candidates.each { rB ->
                def roiA = rA.getROI()
                def roiB = rB.getROI()

                def merge = RoiTools.combineROIs( roiA, roiB, RoiTools.CombineOp.INTERSECT )

                if ( merge.isEmpty() ) return

                def roiA_ratio = merge.getArea() / roiA.getArea() * 100
                def roiB_ratio = merge.getArea() / roiB.getArea() * 100

                if ( ( roiA_ratio > percent_overlap_lim ) || ( roiB_ratio > percent_overlap_lim ) )
                    ( roiA_ratio < roiB_ratio ) ? roisToDelete.add( rB ) : roisToDelete.add( rA )
            }
        }

        logger.info( "{} overlapping detections to be removed", roisToDelete.size() )

        return roisToDelete
    }

    // Get get quantile values for normalization
    def getQuantileMinMax( ImagePlus image, double lower_q, double upper_q ) {
        logger.info( "Using {}% lower quantile and {}% upper quantile", lower_q, upper_q )
        def proc = image.getProcessor().convertToFloatProcessor()
        def perc = new Percentile()

        def lower_val = perc.evaluate( proc.getPixels() as double[], lower_q )
        def upper_val = perc.evaluate( proc.getPixels() as double[], upper_q )

        return [min: lower_val, max: upper_val]
    }

    public void runScript( File data_folder, def min_max ) {
        logger.info( "Running Python Script" )

        def sout = new StringBuilder()
        
        def parameters_strs = this.parameters.collect{ key, value -> return "--${key}=${value}" }

        def pb =  new ProcessBuilder( this.python_executable.getAbsolutePath(),
                                      this.python_script_file.getAbsolutePath(),
                                      data_folder.getAbsolutePath(),
                                      '--min='+min_max['min'],
                                      '--max='+min_max['max'],
                                      *parameters_strs)
                                      .redirectErrorStream( true )
        def process = pb.start()

        logger.info( "Started command: {}", pb.command().join(" "))

        process.consumeProcessOutput( sout, sout )

        // Show what is happening in the log
        while ( process.isAlive() ) {

            if ( sout.size() > 0 ) {
                logger.info( sout.toString() )
                sout.setLength( 0 )
            }

            sleep( 200 )

        }

        logger.info( "Running Python Script Complete" )
    }
}


// All the Stardist Magic is in the Class below
// All imports
import ch.epfl.biop.qupath.utils.*
import ij.IJ
import ij.ImagePlus
import ij.measure.Calibration
import ij.plugin.frame.RoiManager

// To compute image normalization
import org.apache.commons.math3.stat.descriptive.rank.Percentile

// QuPath does not log standard output when declaring Groovy Classes, so we use the logger
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// ROI <> Roi conversion tools
import qupath.imagej.tools.ROIConverterIJ

// Needed when requesting tiles from QuPath
import qupath.lib.geom.ImmutableDimension

// PathObjects
import qupath.lib.objects.*
//import qupath.lib.roi.interfaces.PathArea
import qupath.lib.roi.interfaces.ROI
import qupath.lib.roi.RoiTools

// Helps create temp directory
import java.nio.file.*
import ij.plugin.ChannelSplitter