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

  enterTest = ''
    java -version
    test -d "$ANDROID_HOME/platforms/android-37"
    ruby --version
    bundle --version
  '';
}
