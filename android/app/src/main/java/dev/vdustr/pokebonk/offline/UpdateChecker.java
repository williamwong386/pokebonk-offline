package dev.vdustr.pokebonk.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateChecker {
    private static final String REPO = "williamwong386/pokebonk-offline";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String PROXY = "https://gh-proxy.org/";
    private static final String PREFIX = "https://github.com/" + REPO + "/releases/download/";
    private static final String ASSET = "pokebonk-local.apk";
    private static final String PREF = "pokebonk_update_settings";
    private static final long PERIOD = 12L * 3600_000L;
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);

    private UpdateChecker() {}

    public static void onLaunch(Activity a) {
        SharedPreferences p = prefs(a);
        if (!p.getBoolean("asked", false)) {
            new AlertDialog.Builder(a).setTitle("更新设置")
                .setMessage("是否允许启动时联网检查更新？游戏本身仍可完全离线运行。")
                .setNegativeButton("保持离线", (d,w) -> p.edit()
                    .putBoolean("asked", true).putBoolean("enabled", false).apply())
                .setPositiveButton("允许检查", (d,w) -> {
                    p.edit().putBoolean("asked", true).putBoolean("enabled", true).apply();
                    check(a, false);
                }).show();
            return;
        }
        if (p.getBoolean("enabled", false)) {
            long now = System.currentTimeMillis(), last = p.getLong("last",0);
            if (now < last || now - last >= PERIOD) check(a, false);
        }
    }

    public static void showMenu(Activity a) {
        boolean enabled = prefs(a).getBoolean("enabled",false);
        new AlertDialog.Builder(a).setTitle("PokéBonk 更新")
            .setItems(new String[]{"立即检查更新",
                enabled ? "关闭自动检查" : "开启自动检查", "检查下载并安装"}, (d,w) -> {
                if (w == 0) check(a, true);
                else if (w == 1) {
                    prefs(a).edit().putBoolean("asked",true)
                        .putBoolean("enabled",!enabled).apply();
                    toast(a, !enabled ? "自动检查已开启" : "自动检查已关闭");
                } else install(a);
            }).setNegativeButton("返回",null).show();
    }

    private static void check(Activity a, boolean manual) {
        if (!CHECKING.compareAndSet(false,true)) return;
        new Thread(() -> {
            try {
                JSONObject json = getJson(PROXY + API);
                Release r = parse(json);
                prefs(a).edit().putLong("last",System.currentTimeMillis()).apply();
                if (r == null || cmp(r.version,BuildConfig.VERSION_NAME) <= 0) {
                    if (manual) toast(a,"暂无新版本（需先在个人仓库发布 Release）");
                    return;
                }
                onUi(a, () -> new AlertDialog.Builder(a)
                    .setTitle("发现新版 " + r.version)
                    .setMessage("从 gh-proxy.org 下载更新包，完成后校验 SHA-256 与 APK 签名。")
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("下载",(d,w) -> startDownload(a,r)).show());
            } catch (Exception e) {
                Log.e("PokebonkUpdate","Check failed",e);
                if (manual) toast(a, e.getMessage() != null && e.getMessage().contains("HTTP 404")
                    ? "当前仓库还没有正式发布的版本"
                    : "检查失败：网络异常");
            } finally { CHECKING.set(false); }
        },"pokebonk-check").start();
    }

    private static JSONObject getJson(String target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent","PokebonkUpdater/2");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] bytes = new byte[8192]; int n;
                while ((n = in.read(bytes)) != -1) {
                    if (out.size() + n > 1024*1024) throw new Exception("Metadata too large");
                    out.write(bytes,0,n);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { c.disconnect(); }
    }

    private static Release parse(JSONObject j) throws Exception {
        Matcher m = Pattern.compile("^v(\\d+\\.\\d+\\.\\d+)$")
            .matcher(j.optString("tag_name",""));
        if (!m.matches()) throw new Exception("Unexpected release tag");
        JSONArray assets = j.optJSONArray("assets");
        if (assets == null) return null;
        for (int i=0; i<assets.length();i++) {
            JSONObject x = assets.optJSONObject(i);
            if (x == null || !ASSET.equals(x.optString("name"))) continue;
            String url = x.optString("browser_download_url","");
            String digest = x.optString("digest","").toLowerCase(Locale.ROOT);
            long size = x.optLong("size",0);
            if (!url.startsWith(PREFIX) || !url.endsWith("/" + ASSET)
                || !digest.matches("sha256:[0-9a-f]{64}")
                || size <= 0 || size > 300L*1024*1024) {
                throw new SecurityException("Invalid APK metadata");
            }
            return new Release(m.group(1),url,digest.substring(7),size);
        }
        return null;
    }

    private static void startDownload(Activity a, Release r) {
        try {
            File dir = a.getExternalFilesDir(null);
            if (dir == null) throw new Exception("Storage unavailable");
            File apk = new File(dir,ASSET);
            if (apk.exists() && !apk.delete()) throw new Exception("Cannot remove old APK");
            DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(PROXY+r.url));
            req.setTitle("PokéBonk 更新 " + r.version);
            req.setDescription("下载后返回游戏更新设置进行校验安装");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(a,null,ASSET);
            long id = dm.enqueue(req);
            prefs(a).edit().putLong("download",id).putString("sha",r.sha)
                .putLong("size",r.size).apply();
            toast(a,"已开始后台下载，完成后打开『更新设置 → 检查下载并安装』");
        } catch (Exception e) {
            Log.e("PokebonkUpdate","Download start failed",e);
            toast(a,"无法开始下载，请检查网络及下载服务");
        }
    }

    private static void install(Activity a) {
        long id = prefs(a).getLong("download",-1);
        if (id < 0) { toast(a,"当前没有下载任务"); return; }
        DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (c == null || !c.moveToFirst()) { toast(a,"找不到下载任务"); return; }
            int state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (state == DownloadManager.STATUS_FAILED) { toast(a,"下载失败，请重新检查并下载"); return; }
            if (state != DownloadManager.STATUS_SUCCESSFUL) { toast(a,"仍在下载，请稍后再试"); return; }
        }
        new Thread(() -> {
            try {
                File apk = new File(a.getExternalFilesDir(null),ASSET);
                long expectedSize = prefs(a).getLong("size",0);
                String expected = prefs(a).getString("sha","");
                if (!apk.isFile() || apk.length() != expectedSize ||
                    !expected.matches("[a-f0-9]{64}")) throw new Exception("Missing APK");
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (FileInputStream in = new FileInputStream(apk)) {
                    byte[] bytes = new byte[32768]; int n;
                    while ((n = in.read(bytes)) != -1) md.update(bytes,0,n);
                }
                StringBuilder hash = new StringBuilder();
                for (byte b : md.digest()) hash.append(String.format(Locale.ROOT,"%02x",b&255));
                if (!expected.equals(hash.toString())) throw new SecurityException("SHA mismatch");
                validate(a,apk);
                Uri uri = dm.getUriForDownloadedFile(id);
                if (uri == null) throw new Exception("Download URI missing");
                onUi(a, () -> new AlertDialog.Builder(a)
                    .setTitle("更新包已验证")
                    .setMessage("SHA-256、包名、版本和签名检查通过。是否打开系统安装程序？")
                    .setNegativeButton("稍后",null)
                    .setPositiveButton("安装", (d,w) -> openInstaller(a,uri)).show());
            } catch (Exception e) {
                Log.e("PokebonkUpdate","APK verification failed",e);
                toast(a,"APK 校验失败，拒绝安装（请检查签名和版本）");
            }
        },"pokebonk-verify").start();
    }

    @SuppressWarnings("deprecation")
    private static void validate(Activity a, File apk) throws Exception {
        PackageManager pm = a.getPackageManager();
        int f = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo next = pm.getPackageArchiveInfo(apk.getAbsolutePath(),f);
        PackageInfo current = pm.getPackageInfo(a.getPackageName(),f);
        if (next == null || !a.getPackageName().equals(next.packageName))
            throw new SecurityException("Wrong app package");
        if (versionCode(next) <= versionCode(current))
            throw new SecurityException("Not a newer APK");
        Signature[] x,y;
        if (Build.VERSION.SDK_INT >= 28) {
            if (next.signingInfo == null || current.signingInfo == null)
                throw new SecurityException("Missing signing info");
            x=next.signingInfo.getApkContentsSigners();
            y=current.signingInfo.getApkContentsSigners();
        } else { x=next.signatures; y=current.signatures; }
        if (x==null || y==null || x.length==0 || y.length==0)
            throw new SecurityException("Unsigned APK");
        Set<String> s1=new HashSet<>(),s2=new HashSet<>();
        for (Signature s:x) s1.add(s.toCharsString());
        for (Signature s:y) s2.add(s.toCharsString());
        if (!s1.equals(s2)) throw new SecurityException("Signature mismatch");
    }

    private static void openInstaller(Activity a, Uri uri) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a).setTitle("允许安装更新")
                .setMessage("请允许本应用安装未知来源 APK。返回游戏后再次选择『检查下载并安装』。")
                .setNegativeButton("取消",null)
                .setPositiveButton("打开设置",(d,w) -> a.startActivity(new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:"+a.getPackageName())))).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri,"application/vnd.android.package-archive");
        i.setClipData(ClipData.newRawUri("Pokebonk update",uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { a.startActivity(i); }
        catch (Exception e) { toast(a,"无法打开系统安装界面"); }
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    private static int cmp(String a,String b) {
        int[] x=nums(a),y=nums(b);
        for (int i=0;i<3;i++) if (x[i]!=y[i]) return Integer.compare(x[i],y[i]);
        return 0;
    }
    private static int[] nums(String s) {
        int[] a=new int[3];
        Matcher m=Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(s);
        if (m.find()) for (int i=0;i<3;i++) a[i]=Integer.parseInt(m.group(i+1));
        return a;
    }
    private static SharedPreferences prefs(Activity a) { return a.getSharedPreferences(PREF,Context.MODE_PRIVATE); }
    private static void onUi(Activity a,Runnable r) { a.runOnUiThread(() -> { if (!a.isFinishing() && !a.isDestroyed()) r.run(); }); }
    private static void toast(Activity a,String s) { onUi(a,() -> Toast.makeText(a,s,Toast.LENGTH_LONG).show()); }
    private static final class Release {
        final String version,url,sha; final long size;
        Release(String v,String u,String h,long s) { version=v;url=u;sha=h;size=s; }
    }
}
