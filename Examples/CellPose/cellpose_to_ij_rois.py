import argparse
import os
from pathlib import Path
from cellpose import models
from tifffile import imread
import cv2 as cv
import numpy as np
from csbdeep.utils import normalize, normalize_mi_ma
from stardist import export_imagej_rois
from scipy.ndimage import find_objects


parser = argparse.ArgumentParser(description='Run CellPose and Recover IJ Rois')
parser.add_argument('image_folder', type=Path,
                    help='a folder containing tiff files')
parser.add_argument('--min', type=float,
                    help='minimum normalization value')
parser.add_argument('--max', type=float,
                    help='max normalization value')
parser.add_argument('--diameter', type=float,
                    help='average cell/nucleus diameter. Highly recommended to set for speed reasons')
parser.add_argument('--model', type=str,
                    help='cellpose model name')
args = parser.parse_args()


image_folder = args.image_folder
model = 'cyto'
diameter = args.diameter # If diameter is None, CellPose will try to estimate it. Slow

is_norm_provided = False
if( ( args.min is not None ) & ( args.max is not None ) ):
    min = args.min
    max = args.max
    is_norm_provided = True

if( args.model is not None ):
    model = args.model

# Check if we have GPU Available
use_GPU = models.use_gpu()
print( 'CellPose: GPU activated? %d'%use_GPU )

# Prepare CellPose Model
model = models.Cellpose(gpu=use_GPU, model_type=model)

# Read All Images
image_files = sorted( image_folder.glob('*.tif') )

# Normalize Images and predict
images = []

# Because CellPose can process multiple images in batch on its own,
# we will be faster if we open and process all images though a single
# cellpose call
for image_file in image_files:
    print( 'Opening Image: ', str( image_file.name ), '...' )

    image = imread( str( image_file ) )
    if ( is_norm_provided ):
        print('  Normalizing from manual values')
        norm = normalize_mi_ma( image, min, max )
    else:
        print('  Normalizing automatically')
        norm = normalize( image, 1,99.8, axis= (0,1) )

    images.append(norm)
    
# Run CellPose
masks, flows, styles, diams  = model.eval(images, diameter=diameter, flow_threshold=None, channels=None)

for one_mask, image_file in zip(masks, image_files):
    polygons = []

    slices = find_objects(one_mask.astype(int))
    for i,si in enumerate(slices):
        if si is not None:
            coords = [[],[]]
            sr,sc = si
            mask = (one_mask[sr, sc] == (i+1)).astype(np.uint8)
            contours = cv.findContours(mask, cv.RETR_EXTERNAL, cv.CHAIN_APPROX_NONE)
            pvc, pvr = np.concatenate(contours[-2], axis=0).squeeze().T            
            vr, vc = pvr + sr.start, pvc + sc.start
            coords[0] = vr
            coords[1] = vc

            #sub_polygons.append(coords)
            polygons.append(coords)

    roi_path = image_folder / 'rois' / (str(image_file.name)+'_rois')
    roi_path.parent.mkdir( exist_ok = True )

    print( '    Exporting Polygons' )
    export_imagej_rois( roi_path , [polygons] )

print( 'Script Finished!' )
