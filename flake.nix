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

        # Cross-compile native JNI library for Android ARM64 using NDK via box64
        whisperSrc = pkgs.fetchFromGitHub {
          owner = "ggerganov";
          repo = "whisper.cpp";
          rev = "v1.7.2";
          sha256 = "sha256-y30ZccpF3SCdRGa+P3ddF1tT1KnvlI4Fexx81wZxfTk=";
        };

        llamaSrc = pkgs.fetchFromGitHub {
          owner = "ggerganov";
          repo = "llama.cpp";
          rev = "b4568";
          sha256 = "sha256-85U5tjmF/+Gfrbsx0P8u73jFWwBbjDM/H+rg9yJS1Ew=";
        };

        ndkToolchain = "${androidHome}/ndk/${ndkVersion}/toolchains/llvm/prebuilt/linux-x86_64";

        # Wrapper for NDK clang via box64 - wrap the actual clang binary, not the wrapper script
        ndkSysroot = "${ndkToolchain}/sysroot";

        ndkClang = pkgs.writeShellScriptBin "aarch64-linux-android31-clang" ''
          export BOX64_LD_LIBRARY_PATH="${box64Libs}/lib"
          exec ${pkgs.box64}/bin/box64 ${ndkToolchain}/bin/clang \
            --target=aarch64-linux-android31 \
            --sysroot=${ndkSysroot} \
            "$@"
        '';

        ndkClangxx = pkgs.writeShellScriptBin "aarch64-linux-android31-clang++" ''
          export BOX64_LD_LIBRARY_PATH="${box64Libs}/lib"
          exec ${pkgs.box64}/bin/box64 ${ndkToolchain}/bin/clang++ \
            --target=aarch64-linux-android31 \
            --sysroot=${ndkSysroot} \
            "$@"
        '';

        ndkAr = pkgs.writeShellScriptBin "llvm-ar" ''
          export BOX64_LD_LIBRARY_PATH="${box64Libs}/lib"
          exec ${pkgs.box64}/bin/box64 ${ndkToolchain}/bin/llvm-ar "$@"
        '';

        agentJni = pkgs.stdenv.mkDerivation {
          pname = "agent-jni";
          version = "1.0.0";

          src = ./agent/app/src/main/cpp;

          nativeBuildInputs = [ndkClang ndkClangxx ndkAr];

          buildPhase = ''
            CC=aarch64-linux-android31-clang
            CXX=aarch64-linux-android31-clang++
            AR=llvm-ar

            WHISPER=${whisperSrc}
            LLAMA=${llamaSrc}
            JNI=$src

            CFLAGS="-O3 -fPIC -DNDEBUG -D_XOPEN_SOURCE=600 -DGGML_USE_CPU"
            CXXFLAGS="$CFLAGS -std=c++17"

            GGML_INC="-I$WHISPER/ggml/include -I$WHISPER/ggml/src"

            echo "Building whisper ggml..."
            $CC $CFLAGS $GGML_INC -c $WHISPER/ggml/src/ggml.c -o ggml.o
            $CC $CFLAGS $GGML_INC -c $WHISPER/ggml/src/ggml-alloc.c -o ggml-alloc.o
            $CXX $CXXFLAGS $GGML_INC -c $WHISPER/ggml/src/ggml-backend.cpp -o ggml-backend.o
            $CC $CFLAGS $GGML_INC -c $WHISPER/ggml/src/ggml-quants.c -o ggml-quants.o
            $CC $CFLAGS $GGML_INC -c $WHISPER/ggml/src/ggml-cpu.c -o ggml-cpu.o

            echo "Building whisper..."
            $CXX $CXXFLAGS -I$WHISPER/include -I$WHISPER/ggml/include -I$WHISPER/ggml/src -c $WHISPER/src/whisper.cpp -o whisper.o

            echo "Building JNI wrappers..."
            $CXX $CXXFLAGS -I$WHISPER/include -I$WHISPER/ggml/include \
              -c $JNI/whisper_jni.cpp -o whisper_jni.o

            echo "Linking..."
            $CXX -shared -o libagent_jni.so \
              ggml.o ggml-alloc.o ggml-backend.o ggml-quants.o ggml-cpu.o whisper.o \
              whisper_jni.o \
              -llog -lm -ldl
          '';

          installPhase = ''
            mkdir -p $out/lib/arm64-v8a
            cp libagent_jni.so $out/lib/arm64-v8a/
          '';
        };
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
            ANDROID_NDK_ROOT = "${androidHome}/ndk/${ndkVersion}";
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2Wrapped}/bin/aapt2";

            # Model paths for development
            WHISPER_MODEL = "${whisperModel}";
            LLAMA_MODEL = "${llamaModel}";

            # Prebuilt JNI library
            AGENT_JNI_DIR = "${agentJni}/lib";
          };

          packages.agent-jni = agentJni;

          packages.emulator = androidenv.emulateApp {
            inherit systemImageType platformVersion;
            name = "android-utils";
            abiVersion = "arm64-v8a";
          };
        }
    );
}
