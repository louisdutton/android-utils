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

        # x86_64 libraries for box64 emulation
        pkgsX86 = import nixpkgs {
          system = "x86_64-linux";
          config.allowUnfree = true;
        };

        box64Libs = pkgs.buildEnv {
          name = "box64-libs";
          paths = [
            pkgsX86.glibc
            pkgsX86.libgcc.lib
            pkgsX86.zlib
            pkgsX86.libpng
            pkgsX86.expat
          ];
          pathsToLink = ["/lib"];
        };

        # Helper to wrap x86_64 binaries with box64
        wrapBin = name: path:
          pkgs.writeShellScriptBin name ''
            export BOX64_LD_LIBRARY_PATH="${box64Libs}/lib"
            exec ${pkgs.box64}/bin/box64 ${path} "$@"
          '';

        androidHome = "${androidSdk}/libexec/android-sdk";

        # Wrapped Android SDK tools (aapt2 has no native ARM build)
        aapt2Wrapped = wrapBin "aapt2" "${androidHome}/build-tools/${buildToolsVersion}/aapt2";
      in
        with pkgs; {
          devShells.default = mkShell rec {
            buildInputs = [
              androidSdk
              android-tools # native adb/fastboot
              box64
              gradle
              jdk
              nixd
              alejandra
            ];

            ANDROID_HOME = androidHome;
            ANDROID_NDK_ROOT = "${ANDROID_HOME}/ndk-bundle";
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2Wrapped}/bin/aapt2";
          };

          packages.emulator = androidenv.emulateApp {
            inherit systemImageType platformVersion;
            name = "android-utils";
            abiVersion = "arm64-v8a";
          };
        }
    );
}
