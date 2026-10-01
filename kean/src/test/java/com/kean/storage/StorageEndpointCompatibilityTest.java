package com.kean.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 对象存储兼容性冒烟测试：用与生产完全相同的 minio-java 客户端和调用形状，
 * 打到一个真实 endpoint（本地 RustFS 或任何 S3 兼容服务）上，逐条验证
 * {@code StorageServiceImpl} 依赖的语义。
 *
 * <p>默认不执行（需要一个真实 endpoint），不影响日常 {@code mvn test}。手动运行：
 *
 * <pre>
 * mvn -q -Dtest=StorageEndpointCompatibilityTest test \
 *   -Dstorage.it.endpoint=http://127.0.0.1:9000 \
 *   -Dstorage.it.access-key=&lt;key&gt; \
 *   -Dstorage.it.secret-key=&lt;secret&gt;
 * </pre>
 *
 * <p>也可以省略 access-key / secret-key，改用环境变量 STORAGE_ACCESS_KEY / STORAGE_SECRET_KEY。
 *
 * <p>覆盖的语义（都是 StorageServiceImpl 真正用到的路径）：
 * <ul>
 *   <li>putObject 用 {@code stream(in, length, -1)} —— 与 StorageServiceImpl:95 完全一致；</li>
 *   <li>putObject 用显式 partSize —— 对照组，用来判断前者是否是兼容性风险点；</li>
 *   <li>statObject 读回 Content-Type 与 size —— StorageServiceImpl.contentType() 依赖它；</li>
 *   <li>getObject 字节级比对 —— StorageServiceImpl.open() 依赖它；</li>
 *   <li>statObject 缺失对象必须抛错而不是假装成功 —— exists() / FileController 转 404 依赖它；</li>
 *   <li>带目录前缀的对象键（folder/userId/uuid.ext）—— 与真实键形状一致。</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "storage.it.endpoint", matches = ".+")
class StorageEndpointCompatibilityTest {

    private static final String CONTENT_TYPE = "image/png";

    private static MinioClient client;
    private static String bucket;

    @BeforeAll
    static void setUp() throws Exception {
        String endpoint = required("storage.it.endpoint", "STORAGE_ENDPOINT");
        String accessKey = required("storage.it.access-key", "STORAGE_ACCESS_KEY");
        String secretKey = required("storage.it.secret-key", "STORAGE_SECRET_KEY");
        bucket = System.getProperty("storage.it.bucket", System.getenv().getOrDefault("STORAGE_BUCKET", "kean-it"));

        client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            System.out.println("[storage-it] 已创建测试桶 " + bucket);
        }
        System.out.println("[storage-it] endpoint=" + endpoint + " bucket=" + bucket);
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            System.out.println("[storage-it] 测试完成，桶 " + bucket + " 中的对象已由用例自行清理");
        }
    }

    @Test
    void putWithUnknownPartSize_roundTripsContentTypeAndBytes() throws Exception {
        // 与 StorageServiceImpl:92-97 完全相同的调用形状（长度已知、partSize = -1）
        byte[] content = randomBytes(64 * 1024);
        String key = realKey("avatar");

        try (InputStream input = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(input, content.length, -1)
                    .contentType(CONTENT_TYPE)
                    .build());
        }

        StatObjectResponse stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
        System.out.println("[storage-it] statObject(-1)：contentType=" + stat.contentType() + " size=" + stat.size());
        assertTrue(stat.contentType() != null && stat.contentType().startsWith(CONTENT_TYPE),
                "Content-Type 未能原样读回，浏览器会把图片当附件下载：实际 " + stat.contentType());
        assertEquals(content.length, stat.size(), "size 与上传字节数不一致");

        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] read = in.readAllBytes();
            assertTrue(Arrays.equals(content, read), "getObject 读回的字节与上传内容不一致");
        }

        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }

    @Test
    void putWithExplicitPartSize_roundTrips() throws Exception {
        // 对照组：如果上面的 -1 形状失败、这里成功，说明问题在分片/长度语义，
        // 把 StorageServiceImpl:95 的 -1 换成显式 partSize 即可规避。
        byte[] content = randomBytes(32 * 1024);
        String key = realKey("chat");
        int partSize = 5 * 1024 * 1024;

        try (InputStream input = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(input, content.length, partSize)
                    .contentType(CONTENT_TYPE)
                    .build());
        }

        StatObjectResponse stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
        assertEquals(content.length, stat.size(), "显式 partSize 上传后 size 不一致");

        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            assertTrue(Arrays.equals(content, in.readAllBytes()), "显式 partSize 上传后读回内容不一致");
        }

        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }

    @Test
    void statMissingObject_throwsClientError() {
        // exists() 靠"抛异常"判断不存在；FileController 再把异常转成 404。
        // 这里真正要防的是服务端返回 5xx 或 2xx 之类的意外语义。
        String key = realKey("report") + "-missing-" + UUID.randomUUID();

        Exception thrown;
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            fail("statObject 对不存在的对象竟然成功了，exists() 会永远返回 true");
            return;
        } catch (Exception ex) {
            thrown = ex;
        }

        System.out.println("[storage-it] statObject(缺失) 抛出：" + thrown.getClass().getSimpleName()
                + " / " + thrown.getMessage());

        assertTrue(thrown instanceof ErrorResponseException,
                "缺失对象应返回 S3 错误响应（ErrorResponseException），实际是 " + thrown.getClass().getName());
        String code = ((ErrorResponseException) thrown).errorResponse().code();
        System.out.println("[storage-it] S3 error code = " + code);
        assertNotNull(code, "错误响应里没有 code");
        assertTrue(!code.toLowerCase().contains("internalerror"),
                "缺失对象被当成服务端错误：" + code + "（会让前端拿到 500 而不是 404）");
    }

    private static String realKey(String folder) {
        // 与 StorageServiceImpl:90 的键形状一致：folder/userId/uuid.ext
        return folder + "/" + 10001 + "/" + UUID.randomUUID() + ".png";
    }

    private static byte[] randomBytes(int length) {
        byte[] data = new byte[length];
        new java.util.Random(20261001L).nextBytes(data);
        return data;
    }

    private static String required(String propertyName, String envName) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName);
        }
        if (value == null || value.isBlank()) {
            fail("缺少参数：" + propertyName + "（或环境变量 " + envName + "）");
        }
        return value;
    }
}
