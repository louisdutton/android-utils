#include <jni.h>

#include <android/log.h>

#include <sstream>
#include <string>
#include <utility>
#include <vector>

#ifdef HAVE_VEROVIO
#include "c_wrapper.h"
#endif

namespace {
std::string g_lastDiagnostic;

void SetDiagnostic(std::string value) {
  g_lastDiagnostic = std::move(value);
  __android_log_write(ANDROID_LOG_WARN, "ScoresVerovio", g_lastDiagnostic.c_str());
}

std::string ToUtf8(JNIEnv * env, jstring value) {
  if (value == nullptr)
    return "";
  char const * chars = env->GetStringUTFChars(value, nullptr);
  std::string result = chars == nullptr ? "" : chars;
  if (chars != nullptr)
    env->ReleaseStringUTFChars(value, chars);
  return result;
}

jobjectArray ToJavaStringArray(JNIEnv * env, std::vector<std::string> const & values) {
  jclass stringClass = env->FindClass("java/lang/String");
  jobjectArray pages = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
  for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i) {
    env->SetObjectArrayElement(pages, i, env->NewStringUTF(values[static_cast<size_t>(i)].c_str()));
  }
  return pages;
}

#ifdef HAVE_VEROVIO
std::string ToolkitLog(void * toolkit) {
  char const * log = vrvToolkit_getLog(toolkit);
  return log == nullptr ? "" : log;
}

bool RenderWithInput(
    std::string const & xml,
    std::string const & resourcePath,
    jint targetWidthPx,
    char const * inputFrom,
    std::vector<std::string> * pages) {
  if (resourcePath.empty()) {
    SetDiagnostic("Verovio resource path was empty.");
    return false;
  }

  void * toolkit = vrvToolkit_constructorResourcePath(resourcePath.c_str());
  if (toolkit == nullptr) {
    SetDiagnostic("Verovio toolkit construction failed.");
    return false;
  }

  bool ok = false;
  try {
    enableLogToBuffer(true);
    enableLog(true);
    if (!vrvToolkit_setInputFrom(toolkit, inputFrom)) {
      SetDiagnostic(std::string("Verovio rejected input format '") + inputFrom + "'.");
      vrvToolkit_destructor(toolkit);
      return false;
    }

    std::ostringstream options;
    options << "{"
            << "\"adjustPageHeight\":true,"
            << "\"breaks\":\"auto\","
            << "\"footer\":\"none\","
            << "\"header\":\"none\","
            << "\"pageMarginBottom\":20,"
            << "\"pageMarginLeft\":20,"
            << "\"pageMarginRight\":20,"
            << "\"pageMarginTop\":20,"
            << "\"pageWidth\":" << (targetWidthPx < 320 ? 320 : targetWidthPx)
            << "}";
    if (!vrvToolkit_setOptions(toolkit, options.str().c_str())) {
      SetDiagnostic(std::string("Verovio rejected render options for input '") + inputFrom + "'.");
      vrvToolkit_destructor(toolkit);
      return false;
    }

    if (!vrvToolkit_loadData(toolkit, xml.c_str())) {
      std::string log = ToolkitLog(toolkit);
      SetDiagnostic(
          std::string("Verovio failed to load MusicXML as '") + inputFrom + "'." +
          (log.empty() ? "" : " " + log));
      vrvToolkit_destructor(toolkit);
      return false;
    }

    int const pageCount = vrvToolkit_getPageCount(toolkit);
    for (int page = 1; page <= pageCount; ++page) {
      char const * rendered = vrvToolkit_renderToSVG(toolkit, page, false);
      if (rendered != nullptr && rendered[0] != '\0')
        pages->emplace_back(rendered);
    }
    ok = !pages->empty();
    if (!ok)
      SetDiagnostic(std::string("Verovio loaded MusicXML as '") + inputFrom + "' but returned no SVG pages.");
  } catch (...) {
    SetDiagnostic(std::string("Verovio threw while rendering MusicXML as '") + inputFrom + "'.");
    pages->clear();
    ok = false;
  }
  vrvToolkit_destructor(toolkit);
  return ok;
}
#endif
}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_digital_dutton_essentials_scores_VerovioBridge_renderMusicXmlSvgPages(
    JNIEnv * env, jobject, jstring musicXml, jstring resourcePath, jint targetWidthPx) {
#ifdef HAVE_VEROVIO
  std::string const xml = ToUtf8(env, musicXml);
  std::string const resources = ToUtf8(env, resourcePath);
  g_lastDiagnostic.clear();
  if (xml.empty()) {
    SetDiagnostic("MusicXML input was empty.");
    return ToJavaStringArray(env, {});
  }

  std::vector<std::string> pages;
  if (!RenderWithInput(xml, resources, targetWidthPx, "xml", &pages)) {
    pages.clear();
    RenderWithInput(xml, resources, targetWidthPx, "musicxml-hum", &pages);
  }
  return ToJavaStringArray(env, pages);
#else
  SetDiagnostic("Verovio was not compiled into this build.");
  return ToJavaStringArray(env, {});
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_digital_dutton_essentials_scores_VerovioBridge_lastRenderDiagnostic(
    JNIEnv * env, jobject) {
  return env->NewStringUTF(g_lastDiagnostic.c_str());
}
