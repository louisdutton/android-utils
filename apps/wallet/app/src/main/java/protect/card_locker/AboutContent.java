package protect.card_locker;

import android.content.Context;

import java.io.IOException;

public class AboutContent {

    public Context context;

    public AboutContent(Context context) {
        this.context = context;
    }

    public String getPageTitle() {
        return String.format(context.getString(R.string.about_title_fmt), context.getString(R.string.app_name));
    }

    public String getLicenseHtml() {
        try {
            return Utils.readTextFile(context, R.raw.license);
        }  catch (IOException ignored) {
            return "";
        }
    }

}
