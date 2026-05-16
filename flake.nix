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

        platformVersion = "36";
        buildToolsVersion = "36.0.0";
        comapsCompatBuildToolsVersion = "35.0.0";
        ndkVersion = "28.2.13676358";
        cmakeVersion = "3.22.1";
        buildToolsVersions = pkgs.lib.unique [
          buildToolsVersion
          comapsCompatBuildToolsVersion
        ];
        pythonProtobuf320 = pkgs.python3Packages.buildPythonPackage rec {
          pname = "protobuf";
          version = "3.20.3";
          format = "setuptools";
          src = pkgs.fetchPypi {
            inherit pname version;
            hash = "sha256-LjQnQpyc/+vyWUkb4K9wGJYH82XC9Bx8N2SvbzNxBfI=";
          };
          doCheck = false;
          pythonImportsCheck = ["google.protobuf"];
        };
        comapsPython = pkgs.python3.withPackages (_: [pythonProtobuf320]);
        comapsToolShims = pkgs.runCommand "comaps-tool-shims" {} ''
          mkdir -p "$out/bin"
          ln -s "${pkgs.llvm}/bin/llvm-ar" "$out/bin/clang-ar"
          ln -s "${pkgs.llvm}/bin/llvm-ranlib" "$out/bin/clang-ranlib"
        '';
        systemImageType = "default";
        emulatorAbiVersion =
          if pkgs.stdenv.hostPlatform.isAarch64
          then "arm64-v8a"
          else "x86_64";
        androidSdkArgs = {
          inherit buildToolsVersions;
          platformVersions = [platformVersion];
          cmakeVersions = [cmakeVersion];
          systemImageTypes = [systemImageType];
          abiVersions = [emulatorAbiVersion];
          useGoogleAPIs = false;
          includeSystemImages = true;
          includeCmake = true;
          includeNDK = true;
          ndkVersions = [ndkVersion];
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
            includeCmake
            cmakeVersions
            includeNDK
            ndkVersions
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
              bash
              brotli
              bzip2
              cmake
              comapsPython
              comapsToolShims
              coreutils
              gawk
              getopt
              gradle_9
              git
              icu
              jdk21
              ninja
              nixd
              alejandra
              optipng
              pkg-config
              protobuf_21
              qt6.qtbase
              qt6.qtpositioning
              qt6.qtsvg
              wget
            ];

            ANDROID_HOME = androidHome;
            ANDROID_SDK_ROOT = androidHome;
            ANDROID_NDK_HOME = "${androidHome}/ndk/${ndkVersion}";
            ANDROID_NDK_ROOT = "${androidHome}/ndk/${ndkVersion}";
            JAVA_HOME = jdk21.home;
            PYTHON = "${comapsPython}/bin/python3";

            shellHook = ''
              unset PYTHONPATH
              export PATH="${comapsPython}/bin:$PATH"
            '';
          };

          packages.emulator = androidEmulator;
        }
    );
}
