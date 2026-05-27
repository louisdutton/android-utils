#include <jni.h>

#include <sstream>
#include <string>

namespace {
std::string ToUtf8(JNIEnv * env, jstring value) {
  if (value == nullptr)
    return "";
  char const * chars = env->GetStringUTFChars(value, nullptr);
  std::string result = chars == nullptr ? "" : chars;
  if (chars != nullptr)
    env->ReleaseStringUTFChars(value, chars);
  return result;
}

std::string EscapeXml(std::string const & value) {
  std::string out;
  out.reserve(value.size());
  for (char c : value) {
    switch (c) {
      case '&': out += "&amp;"; break;
      case '<': out += "&lt;"; break;
      case '>': out += "&gt;"; break;
      case '"': out += "&quot;"; break;
      case '\'': out += "&apos;"; break;
      default: out += c; break;
    }
  }
  return out;
}

std::string PlaceholderSvg(std::string const & title, int pageIndex, int pageCount, int width) {
  int const safeWidth = width < 320 ? 320 : width;
  int const height = safeWidth * 4 / 3;
  std::ostringstream svg;
  svg << "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" << safeWidth
      << "\" height=\"" << height << "\" viewBox=\"0 0 " << safeWidth << " " << height << "\">"
      << "<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>"
      << "<text x=\"" << safeWidth / 2 << "\" y=\"64\" text-anchor=\"middle\""
      << " font-size=\"28\" font-family=\"sans-serif\" fill=\"#202124\">"
      << EscapeXml(title.empty() ? "Score" : title) << "</text>";
  int const left = safeWidth / 8;
  int const right = safeWidth - left;
  int const top = 150;
  for (int system = 0; system < 3; ++system) {
    int const y = top + system * 210;
    for (int line = 0; line < 5; ++line) {
      int const lineY = y + line * 18;
      svg << "<line x1=\"" << left << "\" y1=\"" << lineY << "\" x2=\"" << right
          << "\" y2=\"" << lineY << "\" stroke=\"#2b2b2b\" stroke-width=\"2\"/>";
    }
    for (int note = 0; note < 7; ++note) {
      int const cx = left + 76 + note * ((right - left - 120) / 7);
      int const cy = y + 72 - ((note + system) % 5) * 9;
      svg << "<ellipse cx=\"" << cx << "\" cy=\"" << cy
          << "\" rx=\"12\" ry=\"8\" transform=\"rotate(-18 " << cx << " " << cy
          << ")\" fill=\"#006b5c\"/>"
          << "<line x1=\"" << (cx + 10) << "\" y1=\"" << (cy - 2) << "\" x2=\""
          << (cx + 10) << "\" y2=\"" << (cy - 82)
          << "\" stroke=\"#006b5c\" stroke-width=\"4\"/>";
    }
  }
  svg << "<text x=\"" << safeWidth / 2 << "\" y=\"" << (height - 56)
      << "\" text-anchor=\"middle\" font-size=\"20\" font-family=\"sans-serif\""
      << " fill=\"#6b6b6b\">Verovio native bridge placeholder · page "
      << (pageIndex + 1) << " of " << pageCount << "</text></svg>";
  return svg.str();
}
}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_digital_dutton_essentials_scores_VerovioBridge_renderPlaceholderSvgPages(
    JNIEnv * env, jobject, jstring title, jint pageCount, jint targetWidthPx) {
  int const safePageCount = pageCount < 1 ? 1 : pageCount;
  jclass stringClass = env->FindClass("java/lang/String");
  jobjectArray pages = env->NewObjectArray(safePageCount, stringClass, nullptr);
  std::string const titleUtf8 = ToUtf8(env, title);
  for (int i = 0; i < safePageCount; ++i) {
    std::string page = PlaceholderSvg(titleUtf8, i, safePageCount, targetWidthPx);
    env->SetObjectArrayElement(pages, i, env->NewStringUTF(page.c_str()));
  }
  return pages;
}
