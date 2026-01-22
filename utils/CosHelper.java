package tencent.utils;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.region.Region;
import com.qcloud.cos.model.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/** CosHelper: stateless, non-session. Create → use → shutdown per call. */
public final class CosHelper {
	private static final ILogNode LOG = Core.getLogger("TencentCOS");

	private CosHelper() {
	}

	/** Create client (callers must shutdown in finally when using manually). */
	public static COSClient createClient(String secretId, String secretKey, String regionName) {
		COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
		ClientConfig cfg = new ClientConfig(new Region(regionName));
		return new COSClient(cred, cfg);
	}

	/* -------------------- QUICK OPS (auto close) -------------------- */

	public static boolean testConnection(String secretId, String secretKey, String regionName, String bucketName) {
		COSClient c = createClient(secretId, secretKey, regionName);
		try {
			c.headBucket(new HeadBucketRequest(bucketName));
			LOG.info("COS OK: bucket=" + bucketName + ", region=" + regionName);
			return true;
		} catch (Exception e) {
			LOG.error("COS FAIL: " + e.getMessage(), e);
			return false;
		} finally {
			c.shutdown();
		}
	}

	public static String uploadFile(String secretId, String secretKey, String regionName, String bucketName,
			String localFilePath, String cosKey) {
		COSClient c = createClient(secretId, secretKey, regionName);
		try {
			File f = new File(localFilePath);
			if (!f.exists())
				return "❌ Local file not found: " + localFilePath;
			c.putObject(new PutObjectRequest(bucketName, cosKey, f));
			LOG.info("Uploaded: " + cosKey + " from " + localFilePath);
			return "✅ " + cosKey;
		} catch (Exception e) {
			LOG.error("Upload failed: " + cosKey + " - " + e.getMessage(), e);
			return "❌ " + e.getMessage();
		} finally {
			c.shutdown();
		}
	}

	/** Upload จาก Mendix FileDocument → COS */
	public static String uploadFileDocument(
	        IContext ctx,
	        IMendixObject fileDoc,
	        String secretId,
	        String secretKey,
	        String regionName,
	        String bucketName,
	        String cosKeyPrefix
	) {
	    COSClient c = createClient(secretId, secretKey, regionName);

	    bucketName = bucketName == null ? null : bucketName.trim();
	    cosKeyPrefix = cosKeyPrefix == null ? "" : cosKeyPrefix.trim();

	    if (bucketName == null || bucketName.isEmpty()) return "❌ bucketName is empty";

	    // Build a real object key (must not end with "/" only)
	    String filename = (String) fileDoc.getValue(ctx, "Name");
	    if (filename == null || filename.isBlank()) filename = "file.bin";
	    filename = filename.replace("\\", "_").replace("/", "_").replace("\"", "");

	    String key;
	    if (cosKeyPrefix.isEmpty()) {
	        key = filename;
	    } else {
	        // ensure exactly one "/" between prefix and filename
	        if (!cosKeyPrefix.endsWith("/")) cosKeyPrefix += "/";
	        key = cosKeyPrefix + filename;
	    }

	    try (InputStream in = Core.getFileDocumentContent(ctx, fileDoc)) {
	        if (in == null) return "❌ FileDocument content stream is null";

	        byte[] data = readAllBytes(in);
	        if (data.length == 0) return "❌ Upload aborted: File content is 0 bytes";

	        ObjectMetadata meta = new ObjectMetadata();
	        meta.setContentLength(data.length);
	        meta.setContentDisposition("inline; filename=\"" + filename + "\"");

	        PutObjectRequest req = new PutObjectRequest(bucketName, key, new ByteArrayInputStream(data), meta);
	        PutObjectResult result = c.putObject(req);

	        ObjectMetadata verified = c.getObjectMetadata(bucketName, key);

	        String url;
	        try { url = c.getObjectUrl(bucketName, key).toString(); }
	        catch (Exception ex) { url = "(url not available)"; }

	        return "✅ uploaded"
	                + " bucket=[" + bucketName + "]"
	                + " key=[" + key + "]"
	                + " etag=" + result.getETag()
	                + " len=" + verified.getContentLength()
	                + " url=" + url;

	    } catch (Exception e) {
	        return "❌ " + e.getClass().getSimpleName() + ": " + e.getMessage();
	    } finally {
	        c.shutdown();
	    }
	}

	private static byte[] readAllBytes(InputStream in) throws Exception {
	    ByteArrayOutputStream bos = new ByteArrayOutputStream();
	    byte[] buf = new byte[8192];
	    int r;
	    while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
	    return bos.toByteArray();
	}

	public static String downloadToPath(String secretId, String secretKey, String regionName, String bucketName,
			String cosKey, String localFilePath) {
		COSClient c = createClient(secretId, secretKey, regionName);
		try {
			c.getObject(new GetObjectRequest(bucketName, cosKey), new File(localFilePath));
			LOG.info("Downloaded: " + cosKey + " → " + localFilePath);
			return "✅ " + localFilePath;
		} catch (Exception e) {
			LOG.error("Download failed: " + cosKey + " - " + e.getMessage(), e);
			return "❌ " + e.getMessage();
		} finally {
			c.shutdown();
		}
	}

	/** Download จาก COS → เก็บลง Mendix FileDocument (และตั้งชื่อไฟล์จาก cosKey) */
	public static String downloadToFileDocument(IContext ctx, IMendixObject fileDoc, String secretId, String secretKey,
			String regionName, String bucketName, String cosKey) {
		COSClient c = createClient(secretId, secretKey, regionName);
		try (COSObject obj = c.getObject(new GetObjectRequest(bucketName, cosKey))) {
			try (InputStream in = obj.getObjectContent()) {
				Core.storeFileDocumentContent(ctx, fileDoc, in);
			}
			String name = extractFileName(cosKey);
			fileDoc.setValue(ctx, "Name", name);
			LOG.info("Downloaded to FileDocument: " + cosKey + " (Name=" + name + ")");
			return "✅ " + name;
		} catch (Exception e) {
			LOG.error("Download to FileDocument failed: " + cosKey + " - " + e.getMessage(), e);
			return "❌ " + e.getMessage();
		} finally {
			c.shutdown();
		}
	}

	/** ลบไฟล์บน COS */
	public static String deleteObject(String secretId, String secretKey, String regionName, String bucketName,
			String cosKey) {
		COSClient c = createClient(secretId, secretKey, regionName);
		try {
			c.deleteObject(bucketName, cosKey);
			LOG.info("Deleted: " + cosKey);
			return "✅ " + cosKey;
		} catch (Exception e) {
			LOG.error("Delete failed: " + cosKey + " - " + e.getMessage(), e);
			return "❌ " + e.getMessage();
		} finally {
			c.shutdown();
		}
	}

	/** list แบบระบุ prefix + delimiter = "/" เพื่อโครงสร้างคล้ายโฟลเดอร์ */
	public static ObjectListing list(String secretId, String secretKey, String regionName, String bucketName,
			String prefix, boolean folderLike, int maxKeys) {
		COSClient c = createClient(secretId, secretKey, regionName);
		try {
			ListObjectsRequest req = new ListObjectsRequest();
			req.setBucketName(bucketName);
			if (prefix != null && !prefix.isEmpty())
				req.setPrefix(prefix);
			if (folderLike)
				req.setDelimiter("/");
			if (maxKeys > 0)
				req.setMaxKeys(maxKeys);
			ObjectListing ol = c.listObjects(req);
			LOG.info("Listed: bucket=" + bucketName + ", prefix=" + prefix + ", count="
					+ ol.getObjectSummaries().size());
			return ol; // NOTE: caller should read needed fields now; ol is detached data.
		} finally {
			c.shutdown();
		}
	}

	/* -------------------- helpers -------------------- */

	public static String extractFileName(String cosKey) {
		if (cosKey == null || cosKey.isEmpty())
			return "file";
		int p = cosKey.lastIndexOf('/');
		String n = (p >= 0) ? cosKey.substring(p + 1) : cosKey;
		return n.isEmpty() ? "file" : n;
	}
}
