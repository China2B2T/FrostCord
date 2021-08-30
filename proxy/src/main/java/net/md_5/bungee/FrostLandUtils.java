package net.md_5.bungee;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class FrostLandUtils {
    private static void closeAll(Closeable... stream) {
        for (Closeable i : stream) {
            if (i != null) {
                try {
                    i.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static boolean containsHanScript(String s) {
        return s.codePoints().anyMatch(
                codepoint ->
                        Character.UnicodeScript.of(codepoint) == Character.UnicodeScript.HAN);
    }

    public static String doGet(String apiUrl) {
        HttpURLConnection connection = null;
        InputStream is = null;
        BufferedReader br = null;
        String result = null;

        try {
            URL url = new URL(apiUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.connect();

            if (connection.getResponseCode() == 200) {
                is = connection.getInputStream();
                br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder stringBuilder = new StringBuilder();
                String temp;
                while ((temp = br.readLine()) != null) {
                    stringBuilder.append(temp);
                    stringBuilder.append("\n");
                }

                result = stringBuilder.toString();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            closeAll(br, is);

            assert connection != null;
            connection.disconnect();
        }

        return result;
    }

    public static String doPost(String httpUrl, String param) {
        HttpURLConnection connection = null;
        InputStream is = null;
        OutputStream os = null;
        BufferedReader br = null;
        String result = null;
        try {
            URL url = new URL(httpUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);

            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            os = connection.getOutputStream();
            os.write(param.getBytes());
            if (connection.getResponseCode() == 200) {

                is = connection.getInputStream();
                br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));

                StringBuilder stringBuilder = new StringBuilder();
                String temp;
                while ((temp = br.readLine()) != null) {
                    stringBuilder.append(temp);
                    stringBuilder.append("\n");
                }
                result = stringBuilder.toString();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            closeAll(br, is, os);

            assert connection != null;
            connection.disconnect();
        }
        return result;
    }

    public static String fetchUUID(String id) throws UnsupportedEncodingException {
        id = URLEncoder.encode(id, "UTF-8");
        String apiUrl = "http://" + BungeeCord.getInstance().getConfigurationAdapter().getString("api_address", "127.0.0.1:32088");
        String respond = doGet(apiUrl+ "/api/v1/user/query/nickname/" + id);
        JSONObject json = new JSONObject(respond);

        if (json.has("uuid")) {
            return json.getString("uuid");
        } else {
            String queryResult = doPost(apiUrl + "/api/v1/user/create", "uid=" + id + "&premium=" + (containsHanScript(id) ? "0" : "1"));
            JSONObject query = new JSONObject(queryResult);
            if (query.has("uuid")) {
                return query.getString("uuid");
            }
        }

        return null;
    }
}
