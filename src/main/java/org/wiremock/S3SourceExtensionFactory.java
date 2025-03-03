package org.wiremock;

import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

public class S3SourceExtensionFactory implements ExtensionFactory {

  private final String wiremockS3Bucket;

  private final String wiremockS3BasePath;

  public S3SourceExtensionFactory() {
    wiremockS3Bucket = Optional.ofNullable(System.getenv("WIREMOCK_S3_BUCKET"))
                               .orElseThrow(() -> new IllegalArgumentException(
                                   "env variable not found: WIREMOCK_S3_BUCKET"));
    wiremockS3BasePath = Optional.ofNullable(System.getenv("WIREMOCK_S3_BASE_PATH"))
                                 .orElse("");
  }

  @Override
  public List<Extension> create(WireMockServices services) {
    Region region = Optional.ofNullable(System.getenv("AWS_REGION"))
                            .map(Region::of)
                            .orElse(Region.EU_WEST_1);
    S3Client s3 = S3Client.builder()
                          .region(region)
                          .build();
    S3SourceExtension s3SourceExtension = new S3SourceExtension(s3, wiremockS3Bucket, wiremockS3BasePath);
    return List.of(s3SourceExtension);
  }
}
