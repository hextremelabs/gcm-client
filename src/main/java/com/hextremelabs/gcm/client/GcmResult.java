/*
 * Copyright Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hextremelabs.gcm.client;

import javax.persistence.Basic;
import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.ForeignKey;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.Table;
import javax.validation.constraints.Size;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.Serializable;
import java.util.List;

/**
 * GcmResult of a GCM message request that returned HTTP status code 200.
 *
 * <p>
 * If the message is successfully created, the {@link #getMessageId()} returns the message id and
 * {@link #getErrorCodeName()} returns {@literal null}; otherwise, {@link #getMessageId()} returns {@literal null} and
 * {@link #getErrorCodeName()} returns the code of the error.
 *
 * <p>
 * There are cases when a request is accept and the message successfully created, but GCM has a canonical registration
 * id for that device. In this case, the server should update the registration id to avoid rejected requests in the
 * future.
 *
 * <p>
 * In a nutshell, the workflow to handle a result is:
 * <pre>
 *   - Call {@link #getMessageId()}:
 *     - {@literal null} means error, call {@link #getErrorCodeName()}
 *     - non-{@literal null} means the message was created:
 *       - Call {@link #getCanonicalRegistrationId()}
 *         - if it returns {@literal null}, do nothing.
 *         - otherwise, update the server datastore with the new id.
 * </pre>
 *
 * @author Sayo Oladeji (heavily modified, file is almost a rewrite)
 */
@Entity
@Table(name = "gcm_result", catalog = "litedb")
@XmlRootElement
public class GcmResult implements Serializable {

  private static final long serialVersionUID = 1L;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Basic(optional = false)
  @Column(nullable = false)
  private Long id;

  @Column(name = "message", length = 1000)
  @Size(max = 1000)
  private String messageId;

  @Column(name = "canonical_registration_id", length = 100)
  @Size(max = 100)
  private String canonicalRegistrationId;

  @Column(name = "error_code", length = 45)
  @Size(max = 45)
  private String errorCode;

  @Column(name = "success")
  private Integer success;

  @Column(name = "failure")
  private Integer failure;

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "gcm_registration_id", joinColumns = @JoinColumn(name = "gcm_result_id",
      foreignKey = @ForeignKey(name = "FK-gcm_result-failed_registration_ids")))
  private List<String> failedRegistrationIds;

  protected GcmResult() {
  }

  private GcmResult(Builder builder) {
    canonicalRegistrationId = builder.canonicalRegistrationId;
    messageId = builder.messageId;
    errorCode = builder.errorCode;
    success = builder.success;
    failure = builder.failure;
    failedRegistrationIds = builder.failedRegistrationIds;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  /**
   * Gets the message id, if any.
   */
  @XmlElement(name = "messageId")
  public String getMessageId() {
    return messageId;
  }

  public void setMessageId(String messageId) {
    this.messageId = messageId;
  }

  /**
   * Gets the canonical registration id, if any.
   */
  @XmlElement(name = "cannonicalRegistrationId")
  public String getCanonicalRegistrationId() {
    return canonicalRegistrationId;
  }

  public void setCanonicalRegistrationId(String canonicalRegistrationId) {
    this.canonicalRegistrationId = canonicalRegistrationId;
  }

  /**
   * Gets the error code, if any.
   */
  @XmlElement(name = "errorCodeName")
  public String getErrorCodeName() {
    return errorCode;
  }

  public void setErrorCode(String errorCode) {
    this.errorCode = errorCode;
  }

  @XmlElement(name = "success")
  public Integer getSuccess() {
    return success;
  }

  public void setSuccess(Integer success) {
    this.success = success;
  }

  @XmlElement(name = "failure")
  public Integer getFailure() {
    return failure;
  }

  public void setFailure(Integer failure) {
    this.failure = failure;
  }

  @XmlElement(name = "failedRegistrationIds")
  public List<String> getFailedRegistrationIds() {
    return failedRegistrationIds;
  }

  public void setFailedRegistrationIds(List<String> failedRegistrationIds) {
    this.failedRegistrationIds = failedRegistrationIds;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder("[");
    if (messageId != null) {
      builder.append(" messageId=").append(messageId);
    }
    if (canonicalRegistrationId != null) {
      builder.append(" canonicalRegistrationId=")
          .append(canonicalRegistrationId);
    }
    if (errorCode != null) {
      builder.append(" errorCode=").append(errorCode);
    }
    if (success != null) {
      builder.append(" groupSuccess=").append(success);
    }
    if (failure != null) {
      builder.append(" groupFailure=").append(failure);
    }
    if (failedRegistrationIds != null) {
      builder.append(" failedRegistrationIds=").append(failedRegistrationIds);
    }
    return builder.append(" ]").toString();
  }

  public static final class Builder {

    // optional parameters
    private String messageId;
    private String canonicalRegistrationId;
    private String errorCode;
    private Integer success;
    private Integer failure;
    private List<String> failedRegistrationIds;

    public Builder canonicalRegistrationId(String value) {
      canonicalRegistrationId = value;
      return this;
    }

    public Builder messageId(String value) {
      messageId = value;
      return this;
    }

    public Builder errorCode(String value) {
      errorCode = value;
      return this;
    }

    public Builder success(Integer value) {
      success = value;
      return this;
    }

    public Builder failure(Integer value) {
      failure = value;
      return this;
    }

    public Builder failedRegistrationIds(List<String> value) {
      failedRegistrationIds = value;
      return this;
    }

    public GcmResult build() {
      return new GcmResult(this);
    }
  }
}
