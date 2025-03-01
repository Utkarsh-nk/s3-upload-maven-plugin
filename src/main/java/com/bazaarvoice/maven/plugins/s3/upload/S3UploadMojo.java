package com.bazaarvoice.maven.plugins.s3.upload;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.internal.StaticCredentialsProvider;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.Headers;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.transfer.ObjectMetadataProvider;
import com.amazonaws.services.s3.transfer.Transfer;
import com.amazonaws.services.s3.transfer.TransferManager;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;


import java.io.File;

@Mojo(name = "s3-upload")
public class S3UploadMojo extends AbstractMojo
{
  /** Access key for S3. */
  @Parameter(property = "s3-upload.accessKey")
  private String accessKey;

  /** Secret key for S3. */
  @Parameter(property = "s3-upload.secretKey")
  private String secretKey;

  /**
   *  Execute all steps up except the upload to the S3.
   *  This can be set to true to perform a "dryRun" execution.
   */
  @Parameter(property = "s3-upload.doNotUpload", defaultValue = "false")
  private boolean doNotUpload;

  /** The file/folder to upload. */
  @Parameter(property = "s3-upload.source", required = true)
  private File source;

  /** The bucket to upload into. */
  @Parameter(property = "s3-upload.bucketName", required = true)
  private String bucketName;

  /** The file/folder (in the bucket) to create. */
  @Parameter(property = "s3-upload.destination", required = true)
  private String destination;

  /** Force override of endpoint for S3 regions such as EU. */
  @Parameter(property = "s3-upload.endpoint")
  private String endpoint;
  
  /** The http client proxy host */
  @Parameter(property = "proxy.host", defaultValue = "false")
  private String proxyHost;
  
  /** The http client proxy port*/
  @Parameter(property = "proxy.port", defaultValue = "80")
  private int proxyPort;

  /** In the case of a directory upload, recursively upload the contents. */
  @Parameter(property = "s3-upload.recursive", defaultValue = "false")
  private boolean recursive;

  @Override
  public void execute() throws MojoExecutionException
  {
    if (!source.exists()) {
      throw new MojoExecutionException("File/folder doesn't exist: " + source);
    }

    AmazonS3 s3 = getS3Client(accessKey, secretKey , proxyHost , proxyPort);
    if (endpoint != null) {
      s3.setEndpoint(endpoint);
    }

    if (!s3.doesBucketExist(bucketName)) {
      throw new MojoExecutionException("Bucket doesn't exist: " + bucketName);
    }

    if (doNotUpload) {
      getLog().info(String.format("File %s would have be uploaded to s3://%s/%s (dry run)",
              source, bucketName, destination));

      return;
    }

    boolean success = upload(s3, source);
    if (!success) {
      throw new MojoExecutionException("Unable to upload file to S3.");
    }

    getLog().info(String.format("File %s uploaded to s3://%s/%s",
            source, bucketName, destination));
  }

  private static AmazonS3 getS3Client(String accessKey, String secretKey , String proxyHost , int proxyPort)
  {
    AWSCredentialsProvider provider;
    if (accessKey != null && secretKey != null) {
      AWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);
      provider = new StaticCredentialsProvider(credentials);
    } else {
      provider = new DefaultAWSCredentialsProviderChain();
    }
    ClientConfiguration client_config =  new ClientConfiguration();
    if (!proxyHost.equals("false")){
      client_config.setProxyHost(proxyHost);
      client_config.setProxyPort(proxyPort);
    }
    
    return new AmazonS3Client(provider , client_config);
  }

  private boolean upload(AmazonS3 s3, File sourceFile) throws MojoExecutionException
  {
    TransferManager mgr = new TransferManager(s3);

    Transfer transfer;
    if (sourceFile.isFile()) {
      transfer = mgr.upload(new PutObjectRequest(bucketName, destination, sourceFile)
              .withCannedAcl(CannedAccessControlList.BucketOwnerFullControl));
    } else if (sourceFile.isDirectory()) {
      transfer = mgr.uploadDirectory(bucketName, destination, sourceFile, recursive,
              new ObjectMetadataProvider() {
                @Override
                public void provideObjectMetadata(final File file, final ObjectMetadata objectMetadata) {
                  /**
                   * This is a terrible hack, but the SDK as of 1.10.69 does not allow setting ACLs
                   * for directory uploads otherwise.
                   */
                  objectMetadata.setHeader(Headers.S3_CANNED_ACL, CannedAccessControlList.BucketOwnerFullControl);
                }
              });
    } else {
      throw new MojoExecutionException("File is neither a regular file nor a directory " + sourceFile);
    }
    try {
      getLog().debug("Transferring " + transfer.getProgress().getTotalBytesToTransfer() + " bytes...");
      transfer.waitForCompletion();
      getLog().info("Transferred " + transfer.getProgress().getBytesTransferred() + " bytes.");
    } catch (InterruptedException e) {
      return false;
    }

    return transfer.getState() == Transfer.TransferState.Completed;
  }
}
