package org.kotyle.hattip

import java.io.*
import java.net.HttpURLConnection
import java.net.URLConnection

class MultipartHelper(val con: HttpURLConnection, val charset: String) {
    companion object {
        val LINE_FEED = "\r\n"
    }
    val boundary = "chip" + System.identityHashCode(this) + java.lang.Long.toString(System.currentTimeMillis(), 36)
    val os: OutputStream
    val writer: PrintWriter
    init {
        con.useCaches = false
        con.doOutput = true
        con.doInput = true
        con.setRequestProperty("Content-Type","multipart/form-data; boundary=" + boundary)
        os = con.outputStream
        writer = PrintWriter(OutputStreamWriter(os, charset))
    }

    fun addFormField(name: String, value: String) {
        writer.append("--" + boundary).append(LINE_FEED);
        writer.append("Content-Disposition: form-data; name=\"" + name + "\"")
                .append(LINE_FEED);
        writer.append("Content-Type: text/plain; charset=" + charset).append(
                LINE_FEED);
        writer.append(LINE_FEED);
        writer.append(value).append(LINE_FEED);
        writer.flush();
    }

    fun addFilePart(fieldName: String, uploadFile: File) {
        val fileName = uploadFile.name
        writer.append("--" + boundary).append(LINE_FEED);
        writer.append(
                "Content-Disposition: form-data; name=\"" + fieldName
                        + "\"; filename=\"" + fileName + "\"")
                .append(LINE_FEED);
        writer.append(
                "Content-Type: "
                        + URLConnection.guessContentTypeFromName(fileName))
                .append(LINE_FEED);
        writer.append("Content-Transfer-Encoding: binary").append(LINE_FEED);
        writer.append(LINE_FEED);
        writer.flush();

        val inputStream = FileInputStream(uploadFile);
        val buffer = ByteArray(4096);
        var bytesRead:Int = inputStream.read(buffer)
        while (bytesRead != -1) {
            os.write(buffer, 0, bytesRead);
            bytesRead = inputStream.read(buffer)
        }
        os.flush();
        inputStream.close();

        writer.append(LINE_FEED);
        writer.flush();
    }

    fun finish(): Triple<Int, Map<String, List<String>>, ByteArray> {
        writer.append(LINE_FEED).flush();
        writer.append("--" + boundary + "--").append(LINE_FEED);
        writer.close();

        // checks server's status code first
        val status = con.responseCode
        if (status == HttpURLConnection.HTTP_OK) {
            val inputStream = con.inputStream
            val buffer = ByteArrayOutputStream()
            val data = ByteArray(16384)
            var bytesRead:Int = inputStream.read(data, 0, data.size)
            while (bytesRead != -1) {
                buffer.write(data, 0, bytesRead);
                bytesRead = inputStream.read(data, 0, data.size)
            }
            con.disconnect();
            return Triple(HttpURLConnection.HTTP_OK, mutableMapOf<String, List<String>>(), buffer.toByteArray())
        } else {
            return Triple(status, mutableMapOf<String, List<String>>(), "".toByteArray())
        }
    }

}