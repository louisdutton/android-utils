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
        ndkVersion = "27.0.12077973";
        systemImageType = "default";
        androidPkgs = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [buildToolsVersion];
          platformVersions = [platformVersion];
          systemImageTypes = [systemImageType];
          ndkVersions = [ndkVersion];
          useGoogleAPIs = false;
          includeSystemImages = true;
          includeNDK = true;
          includeSources = false;
          includeEmulator = false;
        };
        androidSdk = androidPkgs.androidsdk;

        # AI Models for on-device inference
        whisperModel = pkgs.fetchurl {
          url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin";
          sha256 = "sha256-x3xXZvHO8JtrfUfyG1Rsvd1BV4hrO11tT3CekeZsfCs=";
        };

        llamaModel = pkgs.fetchurl {
          url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf";
          sha256 = "sha256-dKTajJ/bzRW9H20B1iFBDTHG/ACYb162h4JOe5PXqds=";
        };

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
              cmake
              gradle
              jdk
              ninja
              nixd
              alejandra
            ];

            ANDROID_HOME = androidHome;
            ANDROID_NDK_ROOT = "${androidHome}/ndk/${ndkVersion}";
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2Wrapped}/bin/aapt2";

            # Model paths for development
            WHISPER_MODEL = "${whisperModel}";
            LLAMA_MODEL = "${llamaModel}";
          };

          packages.emulator = androidenv.emulateApp {
            inherit systemImageType platformVersion;
            name = "android-utils";
            abiVersion = "arm64-v8a";
          };
        }
    );
}
