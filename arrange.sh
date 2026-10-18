mkdir -p app/src/main/java/com/mkr/player app/src/main/res/mipmap-xxhdpi
mv app_build.gradle app/build.gradle
mv proguard-rules.pro debug.keystore app/
mv AndroidManifest.xml app/src/main/
mv *.java *.kt app/src/main/java/com/mkr/player/
mv ic_launcher.png app/src/main/res/mipmap-xxhdpi/
