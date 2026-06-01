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
        keepassdxNdkVersion = "25.2.9519653";
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
        comapsSource = pkgs.fetchgit {
          url = "https://codeberg.org/comaps/comaps.git";
          rev = "153f7d47b9eca13a51b78f875b37795ef09eed55";
          fetchSubmodules = false;
          hash = "sha256-U2larw4f4ESftwgalYDWn4Y7BL5w6AuDil1MTJyHWnE=";
        };
        comapsThirdParty = rec {
          borders = pkgs.runCommand "comaps-borders-2026.05.06-11" {} ''
            cp -R ${comapsSource}/data/borders "$out"
          '';
          boost = pkgs.fetchzip {
            url = "https://archives.boost.io/release/1.85.0/source/boost_1_85_0.tar.bz2";
            hash = "sha256-ZIZGXwJSlS/wqhexX31aihatQXlUdSzEfA9BADg/GiM=";
          };
          expatRoot = pkgs.fetchzip {
            url = "https://github.com/libexpat/libexpat/archive/refs/tags/R_2_6_4.tar.gz";
            hash = "sha256-ek8/3c8bKG+z7fIM+QCNsH7eoVGAt7z3bXBHZ3QjlS8=";
          };
          expat = "${expatRoot}/expat";
          freetype = pkgs.fetchzip {
            url = "https://github.com/freetype/freetype/archive/refs/tags/VER-2-13-2.tar.gz";
            hash = "sha256-Io9k8xmOKrk+4GSi3PUU60X68T4BpG8dru1/KO+jVRk=";
          };
          gflags = pkgs.fetchzip {
            url = "https://github.com/gflags/gflags/archive/refs/tags/v2.2.2.tar.gz";
            hash = "sha256-4NLd/p72H7ZiFCCVjTfM/rDvZ8CVPMxYpnJ2O1od8ZA=";
          };
          harfbuzz = pkgs.fetchzip {
            url = "https://github.com/harfbuzz/harfbuzz/archive/refs/tags/9.0.0.tar.gz";
            hash = "sha256-2ieCf3ftNk851FZBDPVl+7QHWBqD729KiUxUyxi26Yg=";
          };
          icu = pkgs.fetchzip {
            url = "https://github.com/unicode-org/icu/archive/refs/tags/release-75-1.tar.gz";
            hash = "sha256-jJa5VTu77vx9l5FgPbEWpL/f3GrZLaGCvZA3vVZ+4Q4=";
          };
          jansson = pkgs.fetchzip {
            url = "https://github.com/akheron/jansson/archive/refs/tags/v2.14.1.tar.gz";
            hash = "sha256-ct/EzRDrHkZrCcm98XGCbjbOM2h3AAMldPoTWA5+dAE=";
          };
          protobufUpstream = pkgs.fetchzip {
            url = "https://github.com/protocolbuffers/protobuf/archive/refs/tags/v3.3.0.tar.gz";
            hash = "sha256-PJVYMRGwYvtj+m0rbontjEPL5xFi/zgg18p76tL3qIg=";
          };
          protobuf = pkgs.runCommand "comaps-protobuf-3.3.0-patched" {} ''
            cp -R ${protobufUpstream} "$out"
            chmod -R u+w "$out"

            substituteInPlace "$out/src/google/protobuf/generated_message_table_driven.h" \
              --replace-fail "#define PROTOBUF_CONSTEXPR constexpr" "#define PROTOBUF_CONSTEXPR constexpr

#include <type_traits>"

            substituteInPlace "$out/src/google/protobuf/repeated_field.h" \
              --replace-fail "#ifdef _MSC_VER
// This is required for min/max on VS2013 only.
#include <algorithm>
#endif

#include <iterator>" "#include <algorithm>
#include <iterator>"

            substituteInPlace "$out/src/google/protobuf/stubs/hash.h" \
              --replace-fail "#elif defined(_MSC_VER) && !defined(_STLPORT_VERSION)" "#elif 0  /* Disabled for MSVC compatibility */"
          '';
          pugixml = pkgs.fetchzip {
            url = "https://github.com/zeux/pugixml/archive/refs/tags/v1.15.tar.gz";
            hash = "sha256-t/57lg32KgKPc7qRGQtO/GOwHRqoj78lllSaE/A8Z9Q=";
          };
          vulkanHeaders = pkgs.fetchzip {
            url = "https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.4.322.tar.gz";
            hash = "sha256-YDh67zrLl+ek7oJBQIfCHARa9yAzH8TsMLpGUtNQqjg=";
          };
        };
        scoresOmrModelArchives = {
          segnet = pkgs.fetchurl {
            url = "https://github.com/aicelen/Andromr/releases/download/v1.0/segnet_308_int8.zip";
            hash = "sha256-LHuirYeiDxG1EiznbLJEFnumfC0L6WKsVfdG9u8D83c=";
          };
          encoder = pkgs.fetchurl {
            url = "https://github.com/aicelen/Andromr/releases/download/v1.0/encoder_331_int8.zip";
            hash = "sha256-dd3u+0QCy5XwRU4sweMTBUYwIFmM0W/fSLJGHHOIp5Y=";
          };
          decoder = pkgs.fetchurl {
            url = "https://github.com/aicelen/Andromr/releases/download/v1.0/decoder_331_java.zip";
            hash = "sha256-t7LfG9gqwJma9EIatXv/KmyR4vroLJSXsKcChBH29MU=";
          };
        };
        scoresOmrAssets = pkgs.runCommand "scores-omr-assets-homr-android-2025-08-18" {
          nativeBuildInputs = [pkgs.unzip];
        } ''
          mkdir -p "$out/omr"
          unzip -p ${scoresOmrModelArchives.segnet} \
            segnet_308_int8.tflite \
            > "$out/omr/segmentation.tflite"
          unzip -p ${scoresOmrModelArchives.encoder} \
            encoder_331_int8.tflite \
            > "$out/omr/encoder.tflite"
          unzip -p ${scoresOmrModelArchives.decoder} \
            decoder_331_java.onnx \
            > "$out/omr/decoder.onnx"
          cat > "$out/omr/README.txt" <<'EOF'
HOMR/Andromr Android checkpoint assets pinned from the upstream v1.0 release.
These are packaged for the Scores app native LiteRT/ONNX OMR integration path.
EOF
        '';
        verovioSourceRaw = pkgs.fetchzip {
          url = "https://github.com/rism-digital/verovio/archive/8100cb39604d40102a9c2ce75719136f3fb52a77.tar.gz";
          hash = "sha256-n4ed5zkK3QInEUAaQi9yvJRURb2YinE5FcdVeX5I1xc=";
        };
        verovioSource = pkgs.runCommand "verovio-8100cb39604d40102a9c2ce75719136f3fb52a77-android-source" {} ''
          cp -R ${verovioSourceRaw} "$out"
          chmod -R u+w "$out"
          cat > "$out/include/vrv/git_commit.h" <<'EOF'
////////////////////////////////////////////////////////
/// Git commit version file generated by Nix         ///
////////////////////////////////////////////////////////

#define GIT_COMMIT "-8100cb3"

EOF
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
          ndkVersions = pkgs.lib.unique [
            ndkVersion
            keepassdxNdkVersion
          ];
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
              poppler-utils
              pkg-config
              protobuf_21
              qt6.qtbase
              qt6.qtpositioning
              qt6.qtsvg
              uv
              wget
            ];

            ANDROID_HOME = androidHome;
            ANDROID_SDK_ROOT = androidHome;
            ANDROID_NDK_HOME = "${androidHome}/ndk/${ndkVersion}";
            ANDROID_NDK_ROOT = "${androidHome}/ndk/${ndkVersion}";
            JAVA_HOME = jdk21.home;
            PYTHON = "${comapsPython}/bin/python3";
            COMAPS_BORDERS_SRC = comapsThirdParty.borders;
            COMAPS_BOOST_SRC = comapsThirdParty.boost;
            COMAPS_EXPAT_SRC = comapsThirdParty.expat;
            COMAPS_FREETYPE_SRC = comapsThirdParty.freetype;
            COMAPS_GFLAGS_SRC = comapsThirdParty.gflags;
            COMAPS_HARFBUZZ_SRC = comapsThirdParty.harfbuzz;
            COMAPS_ICU_SRC = comapsThirdParty.icu;
            COMAPS_JANSSON_SRC = comapsThirdParty.jansson;
            COMAPS_PROTOBUF_SRC = comapsThirdParty.protobuf;
            COMAPS_PUGIXML_SRC = comapsThirdParty.pugixml;
            COMAPS_VULKAN_HEADERS_SRC = comapsThirdParty.vulkanHeaders;
            SCORES_OMR_ASSETS_DIR = scoresOmrAssets;
            VEROVIO_SRC_DIR = verovioSource;
            RAIL_SCHEDULE_ASSET_ROOT = "/tmp/grapheneos-essentials-rail-assets";
            RAIL_SCHEDULE_CACHE_DIR = "/tmp/grapheneos-essentials-rail-cache";

            shellHook = ''
              unset PYTHONPATH
              export PATH="${comapsPython}/bin:$PATH"
            '';
          };

          packages.emulator = androidEmulator;
        }
    );
}
