/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.ivy.util.url;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.net.URLDecoder;

import org.apache.ivy.Ivy;
import org.apache.ivy.util.CopyProgressListener;
import org.apache.ivy.util.FileUtil;
import org.apache.ivy.util.Message;

/**
 * 
 */
public class BasicURLHandler extends AbstractURLHandler {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int ERROR_BODY_TRUNCATE_LEN = 512;

    private static final class HttpStatus {
        static final int SC_OK = 200;

        static final int SC_PROXY_AUTHENTICATION_REQUIRED = 407;
        
        private HttpStatus() {
        }
    }

    public URLInfo getURLInfo(URL url) {
        return getURLInfo(url, 0);
    }

    public URLInfo getURLInfo(URL url, int timeout) {
        // Install the IvyAuthenticator
        if ("http".equals(url.getProtocol()) || "https".equals(url.getProtocol())) {
            IvyAuthenticator.install();
        }
        
        URLConnection con = null;
        try {
            url = normalizeToURL(url);
            con = url.openConnection();
            con.setRequestProperty("User-Agent", "Apache Ivy/" + Ivy.getIvyVersion());
            if (con instanceof HttpURLConnection) {
                HttpURLConnection httpCon = (HttpURLConnection) con;
                if (getRequestMethod() == URLHandler.REQUEST_METHOD_HEAD) {
                    httpCon.setRequestMethod("HEAD");
                }
                if (checkStatusCode(url, httpCon)) {
                    String bodyCharset = getCharSetFromContentType(con.getContentType());
                    return new URLInfo(true, httpCon.getContentLength(), con.getLastModified(), bodyCharset);
                }
            } else {
                int contentLength = con.getContentLength();
                if (contentLength <= 0) {
                    return UNAVAILABLE;
                } else {
                    // TODO: not HTTP... maybe we *don't* want to default to ISO-8559-1 here?
                    String bodyCharset = getCharSetFromContentType(con.getContentType());
                    return new URLInfo(true, contentLength, con.getLastModified(), bodyCharset);
                }
            }
        } catch (UnknownHostException e) {
            Message.warn("Host " + e.getMessage() + " not found. url=" + url);
            Message.info("You probably access the destination server through "
                + "a proxy server that is not well configured.");
        } catch (IOException e) {
            Message.error("Server access Error: " + e.getMessage() + " url=" + url);
        } finally {
            disconnect(con);
        }
        return UNAVAILABLE;
    }

    /**
     * Extract the charset from the Content-Type header string, or default to ISO-8859-1 as per
     * rfc2616-sec3.html#sec3.7.1 .
     * 
     * @param contentType
     *            the Content-Type header string
     * @return the charset as specified in the content type, or ISO-8859-1 if unspecified.
     */
    public static String getCharSetFromContentType(String contentType) {

        String charSet = null;

        if (contentType != null) {
            String[] elements = contentType.split(";");
            for (int i = 0; i < elements.length; i++) {
                String element = elements[i].trim();
                if (element.toLowerCase().startsWith("charset=")) {
                    charSet = element.substring("charset=".length());
                }
            }
        }

        if (charSet == null || charSet.length() == 0) {
            // default to ISO-8859-1 as per rfc2616-sec3.html#sec3.7.1
            charSet = "ISO-8859-1";
        }

        return charSet;
    }

    private boolean checkStatusCode(URL url, HttpURLConnection con) throws IOException {
        int status = con.getResponseCode();
        if (status == HttpStatus.SC_OK) {
            return true;
        }
        
        // IVY-1328: some servers return a 204 on a HEAD request
        if ("HEAD".equals(con.getRequestMethod()) && (status == 204)) {
            return true;
        }
        
        Message.debug("HTTP response status: " + status + " url=" + url);
        if (status == HttpStatus.SC_PROXY_AUTHENTICATION_REQUIRED) {
            Message.warn("Your proxy requires authentication.");
        } else if (String.valueOf(status).startsWith("4")) {
            Message.verbose("CLIENT ERROR: " + con.getResponseMessage() + " url=" + url);
        } else if (String.valueOf(status).startsWith("5")) {
            Message.error("SERVER ERROR: " + con.getResponseMessage() + " url=" + url);
        }
        return false;
    }

    public InputStream openStream(URL url) throws IOException {
        // Install the IvyAuthenticator
        if ("http".equals(url.getProtocol()) || "https".equals(url.getProtocol())) {
            IvyAuthenticator.install();
        }

        URLConnection conn = null;
        try {
            url = normalizeToURL(url);
            conn = url.openConnection();
            conn.setRequestProperty("User-Agent", "Apache Ivy/" + Ivy.getIvyVersion());
            conn.setRequestProperty("Accept-Encoding", "gzip,deflate");
            conn.setRequestProperty("Accept", "application/octet-stream, application/json, application/xml, */*");
            if (conn instanceof HttpURLConnection) {
                HttpURLConnection httpCon = (HttpURLConnection) conn;
                if (!checkStatusCode(url, httpCon)) {
                    throw new IOException(
                        "The HTTP response code for " + url + " did not indicate a success."
                                + " See log for more detail.");
                }
            }
            InputStream inStream = getDecodingInputStream(conn.getContentEncoding(),
                                                          conn.getInputStream());
            ByteArrayOutputStream outStream = new ByteArrayOutputStream();

            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = inStream.read(buffer)) > 0) {
                outStream.write(buffer, 0, len);
            }
            return new ByteArrayInputStream(outStream.toByteArray());
        } finally {
            disconnect(conn);
        }
    }
    
    public void download(URL src, File dest, CopyProgressListener l) throws IOException {
        // Install the IvyAuthenticator
        if ("http".equals(src.getProtocol()) || "https".equals(src.getProtocol())) {
            IvyAuthenticator.install();
        }

        URLConnection srcConn = null;
        try {
            src = normalizeToURL(src);
            srcConn = src.openConnection();
            srcConn.setRequestProperty("User-Agent", "Apache Ivy/" + Ivy.getIvyVersion());
            srcConn.setRequestProperty("Accept-Encoding", "gzip,deflate");
            srcConn.setRequestProperty("Accept", "application/octet-stream, application/json, application/xml, */*");
            if (srcConn instanceof HttpURLConnection) {
                HttpURLConnection httpCon = (HttpURLConnection) srcConn;
                int status = httpCon.getResponseCode();
                if (status == 302 || status == 301) {
                   String location = httpCon.getHeaderField("Location");
                    location = URLDecoder.decode(location, "UTF-8");
                    URL next = new URL(location); 
                    download(next,dest,l);
                    disconnect(srcConn);
                    return;
                }else {
                    if (!checkStatusCode(src, httpCon)) {
                        throw new IOException(
                            "The HTTP response code for " + src + " did not indicate a success."
                                    + " See log for more detail.");
                    }
                }
            }

            // do the download
            InputStream inStream = getDecodingInputStream(srcConn.getContentEncoding(),
                                                          srcConn.getInputStream());
            FileUtil.copy(inStream, dest, l);

            // check content length only if content was not encoded
            if (srcConn.getContentEncoding() == null) {
                int contentLength = srcConn.getContentLength();
                if (contentLength != -1 && dest.length() != contentLength) {
                    dest.delete();
                    throw new IOException(
                            "Downloaded file size doesn't match expected Content Length for " + src
                                    + ". Please retry.");
                }
            }
            
            // update modification date
            long lastModified = srcConn.getLastModified();
            if (lastModified > 0) {
                dest.setLastModified(lastModified);
            }
        } finally {
            disconnect(srcConn);
        }
    }

    public void upload(File source, URL dest, CopyProgressListener l) throws IOException {
        if (!"http".equals(dest.getProtocol()) && !"https".equals(dest.getProtocol())) {
            throw new UnsupportedOperationException(
                    "URL repository only support HTTP PUT at the moment");
        }

        // Install the IvyAuthenticator
        IvyAuthenticator.install();

        HttpURLConnection conn = null;
        try {
            dest = normalizeToURL(dest);
            conn = (HttpURLConnection) dest.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("User-Agent", "Apache Ivy/" + Ivy.getIvyVersion());
            conn.setRequestProperty("Accept", "application/octet-stream, application/json, application/xml, */*");
            conn.setRequestProperty("Content-type", "application/octet-stream");
            conn.setRequestProperty("Content-length", Long.toString(source.length()));
            conn.setInstanceFollowRedirects(true);

            Message.debug("Request Headers:" + getHeadersAsDebugString(conn.getRequestProperties()));
            InputStream in = new FileInputStream(source);
            try {
                OutputStream os = conn.getOutputStream();
                FileUtil.copy(in, os, l);
            } finally {
                try {
                    in.close();
                } catch (IOException e) {
                    /* ignored */
                }
            }

            // initiate the connection
            int responseCode = conn.getResponseCode();

            String extra = "";
            InputStream errorStream = conn.getErrorStream();
            InputStream responseStream = conn.getInputStream();
            if (errorStream != null) {
                extra = "; Response Body: " + readTruncated(errorStream, ERROR_BODY_TRUNCATE_LEN,
                        conn.getContentType(), conn.getContentEncoding());
            } else if (responseStream != null) {
                InputStream decodingStream = getDecodingInputStream(conn.getContentEncoding(), responseStream);
                extra = "; Response Body: " + readTruncated(responseStream, ERROR_BODY_TRUNCATE_LEN,
                        conn.getContentType(), conn.getContentEncoding());
            }
            Message.debug("Response Headers:" + getHeadersAsDebugString(conn.getHeaderFields()));
            validatePutStatusCode(dest, responseCode, conn.getResponseMessage() + extra);
        } finally {
            disconnect(conn);
        }
    }

    private String getHeadersAsDebugString(Map<String, List<String>> headers) throws IOException {
        StringBuilder builder = new StringBuilder("");

        if (headers != null) {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                String key = header.getKey();
                if (key != null) {
                    builder.append(header.getKey());
                    builder.append(": ");
                }
                builder.append(String.join("\n    ", header.getValue()));
                builder.append("\n");
            }
        }
        return builder.toString();
    }

    private String readTruncated(InputStream is, int maxLen, String contentType,
                                 String contentEncoding) throws IOException {

        InputStream decodingStream = getDecodingInputStream(contentEncoding, is);
        String charSet = getCharSetFromContentType(contentType);
        ByteArrayOutputStream os = new ByteArrayOutputStream(maxLen);
        try {
            int count = 0;
            int b = decodingStream.read();
            while (count < maxLen && b >= 0) {
                os.write(b);
                count += 1;
                b = decodingStream.read();
            }
            return new String(os.toByteArray(), charSet);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                /* ignored */
            }
        }
    }

    private void disconnect(URLConnection con) {
        if (con instanceof HttpURLConnection) {
            if (!"HEAD".equals(((HttpURLConnection) con).getRequestMethod())) {
                // We must read the response body before disconnecting!
                // Cfr. http://java.sun.com/j2se/1.5.0/docs/guide/net/http-keepalive.html
                // [quote]Do not abandon a connection by ignoring the response body. Doing
                // so may results in idle TCP connections.[/quote]
                readResponseBody((HttpURLConnection) con);
            }
            
            ((HttpURLConnection) con).disconnect();
        } else if (con != null) {
            try {
                con.getInputStream().close();
            } catch (IOException e) {
                // ignored
            }
        }
    }

    /** 
     * Read and ignore the response body. 
     */
    private void readResponseBody(HttpURLConnection conn) {
        byte[] buffer = new byte[BUFFER_SIZE];
        
        InputStream inStream = null;
        try {
            inStream = conn.getInputStream();
            while (inStream.read(buffer) > 0) {
                //Skip content
            }
        } catch (IOException e) {
            // ignore
        } finally {
            if (inStream != null) {
                try {
                    inStream.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        
        InputStream errStream = conn.getErrorStream();
        if (errStream != null) {
            try {
                while (errStream.read(buffer) > 0) {
                    //Skip content
                }
            } catch (IOException e) {
                // ignore
            } finally {
                try {
                    errStream.close();                
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }
}
