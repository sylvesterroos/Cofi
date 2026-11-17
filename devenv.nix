{ pkgs, ... }:

{
  android = {
    enable = true;
    platforms.version = [ "36" "37" ];
    buildTools.version = [ "36.0.0" ];
    abis = [ "x86_64" ];
    emulator.enable = true;
    ndk.enable = false;
    cmake.version = [ ];
  };

  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk25;
    lsp.enable = false;
  };

  languages.ruby = {
    enable = true;
    bundler.enable = true;
    lsp.enable = false;
  };

  scripts.create-emulator-device.exec = ''
    echo no | avdmanager create avd -n cofi -k "system-images;android-36;google_apis_playstore;x86_64" -d pixel_6 --force
  '';

  scripts.start-emulator.exec = ''
    # The bundled libc++ must precede the system one or the emulator fails on a missing symbol.
    export LD_LIBRARY_PATH="$(dirname "$(readlink -f "$ANDROID_HOME/emulator/emulator")")/lib64"
    exec emulator -avd cofi -no-window -no-audio -no-snapshot -gpu swiftshader_indirect "$@"
  '';

  packages = [ pkgs.openssl ];

  scripts.create-release-keystore.exec = ''
    cd "$DEVENV_ROOT"
    test ! -e release.jks || { echo "release.jks bestaat al" >&2; exit 1; }
    password="$(openssl rand -hex 24)"
    keytool -genkeypair -keystore release.jks -alias cofi -keyalg RSA -keysize 4096 \
      -validity 36500 -dname "CN=Cofi" -storepass "$password" -keypass "$password"
    printf 'storeFile=release.jks\nstorePassword=%s\nkeyAlias=cofi\nkeyPassword=%s\n' \
      "$password" "$password" > keystore.properties
    echo "Bewaar release.jks en keystore.properties buiten de repository; zonder deze bestanden kan een update niet over deze installatie heen."
  '';

  scripts.build-apk.exec = ''
    cd "$DEVENV_ROOT"
    test -f keystore.properties || { echo "Draai eerst create-release-keystore" >&2; exit 1; }
    ./gradlew :app:assembleFullRelease
    ls -1 app/build/outputs/apk/full/release/*.apk
  '';

  scripts.install-apk.exec = ''
    cd "$DEVENV_ROOT"
    adb install -r app/build/outputs/apk/full/release/*.apk
  '';

  enterTest = ''
    java -version
    test -d "$ANDROID_HOME/platforms/android-37"
    ruby --version
    bundle --version
  '';
}
