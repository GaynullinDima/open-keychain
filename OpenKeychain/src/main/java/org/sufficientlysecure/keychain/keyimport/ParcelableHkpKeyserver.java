/*
 * Copyright (C) 2016 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2011-2014 Thialfihar <thi@thialfihar.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.keyimport;

import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.pgp.PgpHelper;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.OkHttpClientFactory;
import org.sufficientlysecure.keychain.util.ParcelableProxy;
import org.sufficientlysecure.keychain.util.TlsHelper;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.measite.minidns.Client;
import de.measite.minidns.Question;
import de.measite.minidns.Record;
import de.measite.minidns.record.SRV;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ParcelableHkpKeyserver extends Keyserver implements Parcelable {

    /**
     * pub:%keyid%:%algo%:%keylen%:%creationdate%:%expirationdate%:%flags%
     * <ul>
     * <li>%<b>keyid</b>% = this is either the fingerprint or the key ID of the key.
     * Either the 16-digit or 8-digit key IDs are acceptable, but obviously the fingerprint is best.
     * </li>
     * <li>%<b>algo</b>% = the algorithm number, (i.e. 1==RSA, 17==DSA, etc).
     * See <a href="http://tools.ietf.org/html/rfc2440#section-9.1">RFC-2440</a></li>
     * <li>%<b>keylen</b>% = the key length (i.e. 1024, 2048, 4096, etc.)</li>
     * <li>%<b>creationdate</b>% = creation date of the key in standard
     * <a href="http://tools.ietf.org/html/rfc2440#section-9.1">RFC-2440</a> form (i.e. number of
     * seconds since 1/1/1970 UTC time)</li>
     * <li>%<b>expirationdate</b>% = expiration date of the key in standard
     * <a href="http://tools.ietf.org/html/rfc2440#section-9.1">RFC-2440</a> form (i.e. number of
     * seconds since 1/1/1970 UTC time)</li>
     * <li>%<b>flags</b>% = letter codes to indicate details of the key, if any. Flags may be in any
     * order. The meaning of "disabled" is implementation-specific. Note that individual flags may
     * be unimplemented, so the absence of a given flag does not necessarily mean the absence of the
     * detail.
     * <ul>
     * <li>r == revoked</li>
     * <li>d == disabled</li>
     * <li>e == expired</li>
     * </ul>
     * </li>
     * </ul>
     *
     * @see <a href="http://tools.ietf.org/html/draft-shaw-openpgp-hkp-00#section-5.2">
     * 5.2. Machine Readable Indexes</a>
     * in Internet-Draft OpenPGP HTTP Keyserver Protocol Document
     */
    public static final Pattern PUB_KEY_LINE = Pattern
            .compile("pub:([0-9a-fA-F]+):([0-9]+):([0-9]+):([0-9]+):([0-9]*):([rde]*)[ \n\r]*" // pub line
                            + "((uid:([^:]*):([0-9]+):([0-9]*):([rde]*)[ \n\r]*)+)", // one or more uid lines
                    Pattern.CASE_INSENSITIVE
            );

    /**
     * uid:%escaped uid string%:%creationdate%:%expirationdate%:%flags%
     * <ul>
     * <li>%<b>escaped uid string</b>% = the user ID string, with HTTP %-escaping for anything that
     * isn't 7-bit safe as well as for the ":" character.  Any other characters may be escaped, as
     * desired.</li>
     * <li>%<b>creationdate</b>% = creation date of the key in standard
     * <a href="http://tools.ietf.org/html/rfc2440#section-9.1">RFC-2440</a> form (i.e. number of
     * seconds since 1/1/1970 UTC time)</li>
     * <li>%<b>expirationdate</b>% = expiration date of the key in standard
     * <a href="http://tools.ietf.org/html/rfc2440#section-9.1">RFC-2440</a> form (i.e. number of
     * seconds since 1/1/1970 UTC time)</li>
     * <li>%<b>flags</b>% = letter codes to indicate details of the key, if any. Flags may be in any
     * order. The meaning of "disabled" is implementation-specific. Note that individual flags may
     * be unimplemented, so the absence of a given flag does not necessarily mean the absence of
     * the detail.
     * <ul>
     * <li>r == revoked</li>
     * <li>d == disabled</li>
     * <li>e == expired</li>
     * </ul>
     * </li>
     * </ul>
     */
    public static final Pattern UID_LINE = Pattern
            .compile("uid:([^:]*):([0-9]+):([0-9]*):([rde]*)",
                    Pattern.CASE_INSENSITIVE);

    private static final short PORT_DEFAULT = 11371;
    private static final short PORT_DEFAULT_HKPS = 443;

    private String mUrl;
    private String mOnion;

    public ParcelableHkpKeyserver(@NonNull String url, String onion) {
        mUrl = url.trim();
        mOnion = onion == null ? null : onion.trim();
    }

    public ParcelableHkpKeyserver(@NonNull String url) {
        this(url, null);
    }

    public String getUrl() {
        return mUrl;
    }

    public String getOnion() {
        return mOnion;
    }

    public URI getUrlURI() throws URISyntaxException {
        return getURI(mUrl);
    }

    public URI getOnionURI() throws URISyntaxException {
        return getURI(mOnion);
    }

    private URI getProxiedURL(ParcelableProxy proxy) throws URISyntaxException {
        if (proxy.isTorEnabled()) {
            return getOnionURI();
        } else {
            return getUrlURI();
        }
    }

    /**
     * @param keyserverUrl "<code>hostname</code>" (eg. "<code>pool.sks-keyservers.net</code>"), then it will
     *                     connect using {@link #PORT_DEFAULT}. However, port may be specified after colon
     *                     ("<code>hostname:port</code>", eg. "<code>p80.pool.sks-keyservers.net:80</code>").
     */
    private URI getURI(String keyserverUrl) throws URISyntaxException {
        URI originalURI = new URI(keyserverUrl);

        String scheme = originalURI.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)
                && !"hkp".equalsIgnoreCase(scheme) && !"hkps".equalsIgnoreCase(scheme))) {
            throw new URISyntaxException(scheme, "unsupported scheme!");
        }

        int port = originalURI.getPort();

        if ("hkps".equalsIgnoreCase(scheme)) {
            scheme = "https";
            port = port == -1 ? PORT_DEFAULT_HKPS : port;
        } else if ("hkp".equalsIgnoreCase(scheme)) {
            scheme = "http";
            port = port == -1 ? PORT_DEFAULT : port;
        }

        return new URI(scheme, originalURI.getUserInfo(), originalURI.getHost(), port,
                originalURI.getPath(), originalURI.getQuery(), originalURI.getFragment());
    }

    private String query(String request, @NonNull ParcelableProxy proxy) throws Keyserver.QueryFailedException, HttpError {
        try {
            URL url = new URL(getProxiedURL(proxy).toString() + request);
            Log.d(Constants.TAG, "hkp keyserver query: " + url + " Proxy: " + proxy.getProxy());
            OkHttpClient client = OkHttpClientFactory.getClientPinnedIfAvailable(url, proxy.getProxy());
            Response response = client.newCall(new Request.Builder().url(url).build()).execute();

            String responseBody = response.body().string(); // contains body both in case of success or failure

            if (response.isSuccessful()) {
                return responseBody;
            } else {
                throw new HttpError(response.code(), responseBody);
            }
        } catch (IOException e) {
            Log.e(Constants.TAG, "IOException at HkpKeyserver", e);
            throw new Keyserver.QueryFailedException("Keyserver '" + mUrl + "' is unavailable. Check your Internet connection!" +
                    (proxy.getProxy() == Proxy.NO_PROXY ? "" : " Using proxy " + proxy.getProxy()));
        } catch (TlsHelper.TlsHelperException e) {
            Log.e(Constants.TAG, "Exception in pinning certs", e);
            throw new Keyserver.QueryFailedException("Exception in pinning certs");
        } catch (URISyntaxException e) {
            Log.e(Constants.TAG, "Unsupported keyserver URI", e);
            throw new Keyserver.QueryFailedException("Unsupported keyserver URI");
        }
    }

    /**
     * Results are sorted by creation date of key!
     */
    @Override
    public ArrayList<ImportKeysListEntry> search(String query, ParcelableProxy proxy) throws Keyserver.QueryFailedException,
            Keyserver.QueryNeedsRepairException {
        ArrayList<ImportKeysListEntry> results = new ArrayList<>();

        if (query.length() < 3) {
            throw new Keyserver.QueryTooShortException();
        }

        String encodedQuery;
        try {
            encodedQuery = URLEncoder.encode(query, "UTF8");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
        String request = "/pks/lookup?op=index&options=mr&search=" + encodedQuery;

        String data;
        try {
            data = query(request, proxy);
        } catch (HttpError e) {
            if (e.getData() != null) {
                Log.d(Constants.TAG, "returned error data: " + e.getData().toLowerCase(Locale.ENGLISH));

                if (e.getData().toLowerCase(Locale.ENGLISH).contains("no keys found")) {
                    // NOTE: This is also a 404 error for some keyservers!
                    return results;
                } else if (e.getData().toLowerCase(Locale.ENGLISH).contains("too many")) {
                    throw new Keyserver.TooManyResponsesException();
                } else if (e.getData().toLowerCase(Locale.ENGLISH).contains("insufficient")) {
                    throw new Keyserver.QueryTooShortException();
                } else if (e.getCode() == 404) {
                    // NOTE: handle this 404 at last, maybe it was a "no keys found" error
                    throw new Keyserver.QueryFailedException("Keyserver '" + mUrl + "' not found. Error 404");
                } else {
                    // NOTE: some keyserver do not provide a more detailed error response
                    throw new Keyserver.QueryTooShortOrTooManyResponsesException();
                }
            }

            throw new Keyserver.QueryFailedException("Querying server(s) for '" + mUrl + "' failed.");
        }

        final Matcher matcher = PUB_KEY_LINE.matcher(data);
        while (matcher.find()) {
            final ImportKeysListEntry entry = new ImportKeysListEntry();
            entry.setQuery(query);
            entry.addOrigin(getHostID());

            // group 1 contains the full fingerprint (v4) or the long key id if available
            // see https://bitbucket.org/skskeyserver/sks-keyserver/pull-request/12/fixes-for-machine-readable-indexes/diff
            String fingerprintOrKeyId = matcher.group(1).toLowerCase(Locale.ENGLISH);
            if (fingerprintOrKeyId.length() == 40) {
                entry.setFingerprintHex(fingerprintOrKeyId);
                entry.setKeyIdHex("0x" + fingerprintOrKeyId.substring(fingerprintOrKeyId.length()
                        - 16, fingerprintOrKeyId.length()));
            } else if (fingerprintOrKeyId.length() == 16) {
                // set key id only
                entry.setKeyIdHex("0x" + fingerprintOrKeyId);
            } else {
                Log.e(Constants.TAG, "Wrong length for fingerprint/long key id.");
                // skip this key
                continue;
            }

            try {
                int bitSize = Integer.parseInt(matcher.group(3));
                entry.setBitStrength(bitSize);
                int algorithmId = Integer.decode(matcher.group(2));
                entry.setAlgorithm(KeyFormattingUtils.getAlgorithmInfo(algorithmId, bitSize, null));

                final long creationDate = Long.parseLong(matcher.group(4));
                final GregorianCalendar tmpGreg = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
                tmpGreg.setTimeInMillis(creationDate * 1000);
                entry.setDate(tmpGreg.getTime());
            } catch (NumberFormatException e) {
                Log.e(Constants.TAG, "Conversation for bit size, algorithm, or creation date failed.", e);
                // skip this key
                continue;
            }

            try {
                entry.setRevoked(matcher.group(6).contains("r"));
                entry.setExpired(matcher.group(6).contains("e"));
            } catch (NullPointerException e) {
                Log.e(Constants.TAG, "Check for revocation or expiry failed.", e);
                // skip this key
                continue;
            }

            ArrayList<String> userIds = new ArrayList<>();
            final String uidLines = matcher.group(7);
            final Matcher uidMatcher = UID_LINE.matcher(uidLines);
            while (uidMatcher.find()) {
                String tmp = uidMatcher.group(1).trim();
                if (tmp.contains("%")) {
                    if (tmp.contains("%%")) {
                        // The server encodes a percent sign as %%, so it is swapped out with its
                        // urlencoded counterpart to prevent errors
                        tmp = tmp.replace("%%", "%25");
                    }
                    try {
                        // converts Strings like "Universit%C3%A4t" to a proper encoding form "Universität".
                        tmp = URLDecoder.decode(tmp, "UTF8");
                    } catch (UnsupportedEncodingException ignored) {
                        // will never happen, because "UTF8" is supported
                    } catch (IllegalArgumentException e) {
                        Log.e(Constants.TAG, "User ID encoding broken", e);
                        // skip this user id
                        continue;
                    }
                }
                userIds.add(tmp);
            }
            entry.setUserIds(userIds);
            entry.setPrimaryUserId(userIds.get(0));

            results.add(entry);
        }
        return results;
    }

    @Override
    public String get(String keyIdHex, ParcelableProxy proxy) throws Keyserver.QueryFailedException {
        String request = "/pks/lookup?op=get&options=mr&search=" + keyIdHex;
        Log.d(Constants.TAG, "hkp keyserver get: " + request + " using Proxy: " + proxy.getProxy());
        String data;
        try {
            data = query(request, proxy);
        } catch (HttpError httpError) {
            Log.d(Constants.TAG, "Failed to get key at HkpKeyserver", httpError);
            throw new Keyserver.QueryFailedException("not found");
        }
        if (data == null) {
            throw new Keyserver.QueryFailedException("data is null");
        }
        Matcher matcher = PgpHelper.PGP_PUBLIC_KEY.matcher(data);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new Keyserver.QueryFailedException("data is null");
    }

    @Override
    public void add(String armoredKey, ParcelableProxy proxy) throws Keyserver.AddKeyException {
        try {
            String path = "/pks/add";
            String params;
            try {
                params = "keytext=" + URLEncoder.encode(armoredKey, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new Keyserver.AddKeyException();
            }
            URL url = new URL(getProxiedURL(proxy).toString() + path);

            Log.d(Constants.TAG, "hkp keyserver add: " + url);
            Log.d(Constants.TAG, "params: " + params);


            RequestBody body = RequestBody.create(MediaType.parse("application/x-www-form-urlencoded"), params);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/x-www-form-urlencoded")
                    .addHeader("Content-Length", Integer.toString(params.getBytes().length))
                    .post(body)
                    .build();

            Response response =
                    OkHttpClientFactory.getClientPinnedIfAvailable(url, proxy.getProxy())
                            .newCall(request).execute();

            Log.d(Constants.TAG, "response code: " + response.code());
            Log.d(Constants.TAG, "answer: " + response.body().string());

            if (response.code() != 200) {
                throw new Keyserver.AddKeyException();
            }

        } catch (IOException e) {
            Log.e(Constants.TAG, "IOException", e);
            throw new Keyserver.AddKeyException();
        } catch (TlsHelper.TlsHelperException e) {
            Log.e(Constants.TAG, "Exception in pinning certs", e);
            throw new Keyserver.AddKeyException();
        } catch (URISyntaxException e) {
            Log.e(Constants.TAG, "Unsupported keyserver URI", e);
            throw new Keyserver.AddKeyException();
        }
    }

    private String getHostID() {
        try {
            return (new URI(mUrl)).getHost();
        } catch (URISyntaxException e) {
            return mUrl;
        }
    }

    @Override
    public String toString() {
        return getHostID();
    }

    /**
     * Tries to find a server responsible for a given domain
     *
     * @return A responsible Keyserver or null if not found.
     */
    public static ParcelableHkpKeyserver resolve(String domain) {
        try {
            Record[] records = new Client().query(new Question("_hkp._tcp." + domain, Record.TYPE.SRV)).getAnswers();
            if (records.length > 0) {
                Arrays.sort(records, new Comparator<Record>() {
                    @Override
                    public int compare(Record lhs, Record rhs) {
                        if (lhs.getPayload().getType() != Record.TYPE.SRV) return 1;
                        if (rhs.getPayload().getType() != Record.TYPE.SRV) return -1;
                        return ((SRV) lhs.getPayload()).getPriority() - ((SRV) rhs.getPayload()).getPriority();
                    }
                });
                Record record = records[0]; // This is our best choice
                if (record.getPayload().getType() == Record.TYPE.SRV) {
                    SRV payload = (SRV) record.getPayload();
                    return new ParcelableHkpKeyserver(payload.getName() + ":" + payload.getPort());
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static class HttpError extends Exception {
        private static final long serialVersionUID = 1718783705229428893L;
        private int mCode;
        private String mData;

        HttpError(int code, String data) {
            super("" + code + ": " + data);
            mCode = code;
            mData = data;
        }

        public int getCode() {
            return mCode;
        }

        public String getData() {
            return mData;
        }
    }

    protected ParcelableHkpKeyserver(Parcel in) {
        mUrl = in.readString();
        mOnion = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mUrl);
        dest.writeString(mOnion);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ParcelableHkpKeyserver> CREATOR = new Creator<ParcelableHkpKeyserver>() {
        @Override
        public ParcelableHkpKeyserver createFromParcel(Parcel in) {
            return new ParcelableHkpKeyserver(in);
        }

        @Override
        public ParcelableHkpKeyserver[] newArray(int size) {
            return new ParcelableHkpKeyserver[size];
        }
    };
}
