package com.nhatnam.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponse {
  private long userId;
  private String accessToken;
  private boolean isLockAccount;
  private String bscWalletAddress;
}
