package com.nhatnam.server.utils;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

public class CredentialHelper {
    public static String extractUsernameFromContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // Extract user details
        Object principal = authentication.getPrincipal();

        if (principal instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        } else {
            return "";
        }
    }
}
