{
  description = "Android utility apps monorepo";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    nixpkgs,
    flake-utils,
    ...
  }:
    flake-utils.lib.eachDefaultSystem (
      system: let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        platformVersion = "35";
        buildToolsVersion = "35.0.0";
        systemImageType = "default";
        androidPkgs = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [buildToolsVersion];
          platformVersions = [platformVersion];
          systemImageTypes = [systemImageType];
          useGoogleAPIs = false;
          includeSystemImages = true;
        };
        androidSdk = androidPkgs.androidsdk;

        kotlin-lsp = pkgs.stdenv.mkDerivation {
          pname = "kotlin-lsp";
          version = "0.253.10629";
          nativeBuildInputs = [pkgs.makeWrapper];
          src = pkgs.fetchzip {
            url = "https://download-cdn.jetbrains.com/kotlin-lsp/0.253.10629/kotlin-0.253.10629.zip";
            sha256 = "sha256-LCLGo3Q8/4TYI7z50UdXAbtPNgzFYtmUY/kzo2JCln0=";
            stripRoot = false;
          };

          installPhase = ''
            mkdir -p $out/bin $out/share/kotlin-lsp
            cp -r lib native $out/share/kotlin-lsp/

            makeWrapper ${pkgs.jdk}/bin/java $out/bin/kotlin-lsp \
              --add-flags "--add-opens java.base/java.io=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/java.lang=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/java.lang.ref=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/java.lang.reflect=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/java.net=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/java.nio=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/java.nio.charset=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/java.text=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/java.time=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/java.util=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/java.util.concurrent=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/java.util.concurrent.locks=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/jdk.internal.vm=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/sun.net.dns=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/sun.nio.ch=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/sun.nio.fs=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/sun.security.ssl=ALL-UNNAMED" \
              --add-flags "--add-opens java.base/sun.security.util=ALL-UNNAMED" \
              --add-flags "--add-opens java.desktop/java.awt=ALL-UNNAMED" \
              --add-flags "--add-opens java.desktop/java.awt.event=ALL-UNNAMED" \
              --add-flags "--add-opens java.desktop/java.awt.font=ALL-UNNAMED" \
              --add-flags "--add-opens java.desktop/javax.swing=ALL-UNNAMED" \
              --add-flags "--add-opens java.desktop/sun.awt=ALL-UNNAMED" \
              --add-flags "--add-opens java.desktop/sun.font=ALL-UNNAMED" \
              --add-flags "--add-opens java.desktop/sun.java2d=ALL-UNNAMED" \
              --add-flags "-cp '$out/share/kotlin-lsp/lib/*'" \
              --add-flags "com.jetbrains.ls.kotlinLsp.KotlinLspServerKt"
          '';
        };
      in
        with pkgs; {
          devShells.default = mkShell rec {
            buildInputs = [
              androidSdk
              gradle
              jdk
              nixd
              alejandra
              kotlin-lsp
            ];

            ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
            ANDROID_NDK_ROOT = "${ANDROID_HOME}/ndk-bundle";
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${ANDROID_HOME}/build-tools/${buildToolsVersion}/aapt2";
          };

          packages.emulator = androidenv.emulateApp {
            inherit systemImageType platformVersion;
            name = "android-utils";
            abiVersion = "arm64-v8a";
          };
        }
    );
}
