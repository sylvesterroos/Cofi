{ pkgs, ... }:

{
  android = {
    enable = true;
    platforms.version = [ "37" ];
    buildTools.version = [ "36.0.0" ];
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

  enterTest = ''
    java -version
    test -d "$ANDROID_HOME/platforms/android-37"
    ruby --version
    bundle --version
  '';
}
