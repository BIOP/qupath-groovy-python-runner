import warnings

import argparse

import sys

from stardist.models import StarDist2D
from stardist import export_imagej_rois

from pathlib import Path
from csbdeep.utils import normalize, normalize_mi_ma

from tifffile import imread

# Define arguments we want to use
parser = argparse.ArgumentParser(description='Run StarDist and Recover IJ Rois')
parser.add_argument('image_folder', type=Path,
                    help='a folder containing tiff files')
parser.add_argument('--min', type=float,
                    help='minimum normalization value')
parser.add_argument('--max', type=float,
                    help='max normalization value')
parser.add_argument('--model_folder', type=Path,
                    help='path to where the StarDist models are stored')
parser.add_argument('--model', type=str,
                    help='StarDist model name')
parser.add_argument('--prob_thresh', type=float,
                    help='Probability threshold [0,1]')
parser.add_argument('--nms_thresh', type=float,
                    help='Nonmaximum suppression threshold [0,1]')
args = parser.parse_args()


# First argument should be image folder and second one should be model path. Third argument is the model name
input_arguments = sys.argv
print( 'Arguments: '+str(input_arguments) )

image_folder = args.image_folder
model_folder = args.model_folder

model_name = args.model

prob_thresh= args.prob_thresh # If None, then StarDist will use the value stored in the model

nms_thresh= args.nms_thresh # If None, then StarDist will use the value stored in the model

is_norm_provided = False
if( ( args.min is not None ) & ( args.max is not None ) ):
    min = args.min
    max = args.max
    is_norm_provided = True
    
print( 'Models Folder:',  model_folder )
print( '    Using Model Name:', model_name )

# Initialize the StarDist Model
model = StarDist2D(None, name=model_name , basedir = model_folder )

# Read All Images
image_files = sorted(image_folder.glob('*.tif'))

# Normalize Images and predict
for image_file in image_files:
    print( 'Processing Image: ', str( image_file.name ), '...' )

    image = imread( str( image_file ) )
    if ( is_norm_provided ):
        norm = normalize_mi_ma( image, min, max )
    else:
        norm = normalize( image, 1,99.8, axis = (0,1) )

    labels, polygons = model.predict_instances( norm, prob_thresh=prob_thresh, nms_thresh=nms_thresh )

    # Create a 'rois' folder inside the same directory and make sure that
    # the images are saved with the same name as the image (with extension), with suffix `_rois.zip`
    
    roi_path = image_folder / 'rois' / (str( image_file.name )+'_rois')
    roi_path.parent.mkdir( exist_ok = True )

    if ( polygons['coord'].shape[0] > 0 ):
        print( 'Exporting Polygons' )
        export_imagej_rois( roi_path , polygons['coord'] )

print( 'Script Finished!' )
