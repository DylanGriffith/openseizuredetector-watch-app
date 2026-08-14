# AndroidWear-watch-app

This repository is the source code for a watch app for an Android Wear (Wear OS) watch app, that was developed by  @DylanGriffith.   

It is compatible with the AndroidWear Data Source in the OSD Android App Version 5.0 and 5.1 (https://github.com/OpenSeizureDetector/Android_Pebble_SD).

It is not packaged nicely - the way I run it is to build this repository using Android Studio and run it on the app via Wifi Debugging.    Note that to keep Wifi Debugging working, you have to prevent
the watch display going to sleep (at least you do on my Samsung Galaxy Watch 7) - you can set this in the developer actions so that it does not go to sleep when it is charging.

The initial results are disappointing - the battery drains in just over 6 hours, and the data transfer to the phone seems to not happen at regular intervals.   See the issues associated with this repository
and the discussion here:  https://github.com/orgs/OpenSeizureDetector/discussions/69

Note that the working version is in branch rebuild-v2 until I merge it into main....

Graham (graham@openseizuredetector.org.uk)
