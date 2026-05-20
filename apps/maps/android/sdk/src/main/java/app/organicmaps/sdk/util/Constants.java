package app.organicmaps.sdk.util;

public final class Constants
{
  public static final int KB = 1024;
  public static final int MB = 1024 * 1024;
  public static final int GB = 1024 * 1024 * 1024;

  static final int READ_TIMEOUT_MS = 10000;

  public static class Url
  {
    public static final String SHORT_SHARE_PREFIX = "cm://";
    public static final String HTTP_SHARE_PREFIX = "https://comaps.at/";

    public static final String MAILTO_SCHEME = "mailto:";
    public static final String MAIL_SUBJECT = "?subject=";
    public static final String MAIL_BODY = "&body=";

    public static final String OSM_REGISTER = "https://www.openstreetmap.org/user/new";

    private Url() {}
  }

  public static class Vendor
  {
    public static final String HUAWEI = "HUAWEI";
    public static final String XIAOMI = "XIAOMI";
  }

  private Constants() {}
}
