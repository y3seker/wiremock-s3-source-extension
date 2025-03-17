package org.wiremock;

import com.github.tomakehurst.wiremock.common.ContentTypes;
import com.github.tomakehurst.wiremock.common.InvalidInputException;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.MappingsLoaderExtension;
import com.github.tomakehurst.wiremock.extension.StubLifecycleListener;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.stubbing.StubMappingCollection;
import com.github.tomakehurst.wiremock.stubbing.StubMappings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

public class S3SourceExtension implements StubLifecycleListener, MappingsLoaderExtension {

  private final static String JSON_EXTENSION = ".json";

  private final S3Client s3;

  private final String bucket;

  private final String basePath;

  private List<String> initiallyLoadedStubIds;

  public S3SourceExtension(S3Client s3, String bucket, String basePath) {
    this.s3 = s3;
    this.bucket = bucket;
    this.basePath = basePath;
  }

  @Override
  public String getName() {
    return "s3-source-extension";
  }

  @Override
  public void loadMappingsInto(StubMappings stubMappings) {
    List<S3FileContent> s3Objects = getAllFileContentsFromS3();
    List<StubMapping> stubs = s3Objects.stream()
                                       .flatMap(this::mapToStubStream)
                                       .filter(Objects::nonNull)
                                       .collect(Collectors.toList());
    initiallyLoadedStubIds = stubs.stream().map(StubMapping::getId).map(UUID::toString).collect(Collectors.toList());
    stubs.forEach(stub -> {
      try {
        stubMappings.addMapping(stub);
      } catch (InvalidInputException e) {
        if (e.getErrors().first().getCode() == 109) { // duplicate id
          System.err.println(e.getErrors().first().getDetail());
        } else {
          throw e;
        }
      }
    });
  }

  private Stream<StubMapping> mapToStubStream(S3FileContent s3FileContent) {
    String json = s3FileContent.content;
    if (json.contains("\"mappings\"")) {
      return Json.read(json, StubMappingCollection.class)
                 .getMappings()
                 .stream();
    }
    return Stream.of(StubMapping.buildFrom(json));
  }

  private List<S3FileContent> getAllFileContentsFromS3() {
    List<S3Object> objects = getAllObjectsV2();
    return objects.stream()
                  .filter(object -> object.key().endsWith(JSON_EXTENSION))
                  .parallel()
                  .map(object -> new S3FileContent(object.key(), getFileContent(object.key())))
                  .collect(Collectors.toList());
  }

  private List<S3Object> getAllObjectsV2() {
    String nextContinuationToken = null;
    ArrayList<S3Object> result = new ArrayList<>();
    do {
      ListObjectsV2Response objects = s3.listObjectsV2(ListObjectsV2Request.builder()
                                                                           .bucket(bucket)
                                                                           .prefix(basePath)
                                                                           .continuationToken(nextContinuationToken)
                                                                           .build());
      nextContinuationToken = objects.nextContinuationToken();
      result.addAll(objects.contents());
    } while (nextContinuationToken != null);
    System.out.println("Number of objects in the bucket: " + result.size());
    return result;
  }

  private String getFileContent(String key) {
    GetObjectRequest request = GetObjectRequest.builder()
                                               .key(key)
                                               .bucket(bucket)
                                               .build();
    return s3.getObjectAsBytes(request).asUtf8String();
  }

  @Override
  public void afterStubCreated(StubMapping stub) {
    if (initiallyLoadedStubIds.contains(stub.getId().toString())) {
      return;
    }
    //
    saveStub(stub);
  }

  @Override
  public void afterStubEdited(StubMapping oldStub, StubMapping newStub) {
    saveStub(newStub);
  }

  @Override
  public void afterStubRemoved(StubMapping stub) {
    deleteStub(stub);
  }

  private void saveStub(StubMapping stub) {
    try {
      String key = getKey(stub);
      s3.putObject(PutObjectRequest.builder()
                                   .bucket(bucket)
                                   .key(key)
                                   .contentType(ContentTypes.APPLICATION_JSON)
                                   .build(),
                   RequestBody.fromBytes(Json.toByteArray(stub)));
    } catch (Exception e) {
      System.err.println(e.getMessage());
    }
  }

  private String getKey(StubMapping stub) {
    return basePath + stub.getId().toString() + JSON_EXTENSION;
  }

  private void deleteStub(StubMapping stub) {
    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket)
                                       .key(getKey(stub))
                                       .build());
  }

  static class S3FileContent {

    private final String key, content;

    public S3FileContent(String key, String content) {
      this.key = key;
      this.content = content;
    }
  }
}
