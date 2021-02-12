# Running Python Segmentation Pipelines from QuPath

The goal of this project is to serve as a proof-of-concept that allows for the use of Python to predict segmentations from large images in QuPath.
It was authored by [Olivier Burri](https://github.com/lacan) for the BioImaging and Optics Platform (BIOP) at the Ecole Polytechnique Fédérale de Lausanne

**If you use this project please make sure to cite this repository and the actual deep learning algorithm publication.**

This script does the following:
1. Break down a QuPath region into manageable images
2. Save the images to a temporary folder
3. Run a python script (ideally from a virtual environment) that will output a series or ImageJ Roi .zip files
4. Reimport the ROIs into QuPath at the right places while solving overlaps

## Getting started: What you need

To use this tool, you need: 
1. An environment that runs your favorite deep learning algorithm
2. A python script that takes a directory of tiff images as argument and outputs ImageJ RoiSets as .zip files in a subdirectory called 'rois'
3. A customized version of the `PythonRoiRunner.groovy` file open in QuPath

You will find two examples with StarDist and CellPose in the Examples folder of this repository.

## From an Image to a ROISet .zip file
While there are other ways to reimport data into Fiji from labeled images, choosing to do this via zip files limits the size of the temporary folders we create 
and also shortens the time spent reading and writing intermediate files. 

Thanks to Uwe Schmidt, there is a `export_imagej_rois` method inside StarDist that can be adjusted to suit your needs. 
You can see an example in the `cellpose_to_ij_rois.py` file which converts a mask into ImageJ Rois with it

## Example: StarDist

The python script `stardist-to-ij-rois.py` can be placed wherever you want, simply know its location.
Suppose your StarDist environment is located at
`D:\environments\stardist`
And `stardist-to-ij-rois.py` is in
`D:\qupath-groovy-python-runner\Examples\StarDist\stardist_to_ij_rois.py`

Open the file `PythonRoiRunner-StarDist.groovy` to see how it was configured. Draw a region and hit run!
You may need to adjust the `def preprocess...` step to make sure you export the right channel for StarDist on your data 

### Important
As this is StarDist as it is run natively from within Python, the model should be the raw StarDist model, not a zip file you obtain from exporting the model.

## Example: CellPose

The python script `cellpose-to-ij-rois.py` can be placed wherever you want, simply know its location.
Suppose your CellPose environment is configured and located at
`D:\environments\cellpose`
And `stardist-to-ij-rois.py` is in
`D:\qupath-groovy-python-runner\Examples\CellPose\cellpose_to_ij_rois.py`

Open the file `PythonRoiRunner-CellPose.groovy` to see how it was configured. Draw a region and hit run!
You may need to adjust the `def preprocess...` step to make sure you export the right channel for CellPose on your data 

## How do I create my StarDist or CellPose environment!?
We do not cover how to install your virtual environment with GPU support here, but you do not necessarily need it. CPU is usually enough for inference. 

You can read the [CellPose](http://www.github.com/mouseland/cellpose)
 and [StarDist](https://github.com/mpicbg-csbd/stardist) GitHub pages to see how to best install that environment

**NOTE: In the case of CellPose, you also need to install StarDist in the environment, as we make use of the `export_imagej_rois` method created by Uwe Schmidt to export polygons as ImageJ RoiSets.**

### Minimal Requirements and Setup for Windows
Install Python 3.7 or 3.8 on your system and you can run the following commands to get the same environments as shown above

#### Create a folder that will contain your environments
```
mkdir D:\environments
```

#### StarDist Environment
```
python -m venv D:\environments\stardist
D:\environments\stardist\Scripts\activate
python -m pip install --upgrade pip
pip install -r D:\qupath-groovy-python-runner\Examples\StarDist\requirements-stardist-cpu.txt
```

### CellPose Environment
```
python -m venv D:\environments\cellpose
D:\environments\cellpose\Scripts\activate
python -m pip install --upgrade pip
pip install -r D:\qupath-groovy-python-runner\Examples\CellPose\requirements-cellpose-cpu.txt
```

## NOTES

### Future Work
The `PythonRoiRunner.groovy` will most likely be ported to a Java Class to use directly within QuPath, so that we do not need to copy the PythonRoiRunner class into each new script

Creating a minimal PythonRoiRunner to run within Fiji (no tiling needed, just running the python code and getting the RoiSet back) is also planned, but help is always welcome!
