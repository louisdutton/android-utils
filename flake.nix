{
  description = "GrapheneOS Essentials Android app suite";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    nixpkgs,
    flake-utils,
    ...
  }:
    flake-utils.lib.eachSystem ["aarch64-darwin" "x86_64-darwin"] (
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
        emulatorAbiVersion =
          if pkgs.stdenv.hostPlatform.isAarch64
          then "arm64-v8a"
          else "x86_64";
        androidSdkArgs = {
          buildToolsVersions = [buildToolsVersion];
          platformVersions = [platformVersion];
          systemImageTypes = [systemImageType];
          abiVersions = [emulatorAbiVersion];
          useGoogleAPIs = false;
          includeSystemImages = true;
          includeNDK = false;
          includeSources = false;
          includeEmulator = true;
        };
        androidPkgs = pkgs.androidenv.composeAndroidPackages {
          inherit
            (androidSdkArgs)
            buildToolsVersions
            platformVersions
            systemImageTypes
            abiVersions
            useGoogleAPIs
            includeSystemImages
            includeNDK
            includeSources
            includeEmulator
            ;
        };
        androidSdk = androidPkgs.androidsdk;
        androidHome = "${androidSdk}/libexec/android-sdk";
        androidEmulator = pkgs.androidenv.emulateApp {
          name = "grapheneos-essentials-emulator";
          platformVersion = platformVersion;
          abiVersion = emulatorAbiVersion;
          systemImageType = systemImageType;
          deviceName = "grapheneos-essentials";
          sdkExtraArgs = androidSdkArgs;
          configOptions = {
            "hw.keyboard" = "yes";
            "hw.ramSize" = "4096";
          };
          androidEmulatorFlags = "-no-snapshot-save";
        };
      in
        with pkgs; {
          devShells.default = mkShell rec {
            buildInputs = [
              androidSdk
              androidEmulator
              android-tools
              gradle
              jdk21
              nixd
              alejandra
            ];

            ANDROID_HOME = androidHome;
            ANDROID_SDK_ROOT = androidHome;
            JAVA_HOME = jdk21.home;
          };

          packages.emulator = androidEmulator;
        }
    );
}
