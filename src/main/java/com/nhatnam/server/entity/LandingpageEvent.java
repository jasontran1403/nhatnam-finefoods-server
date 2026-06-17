package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "landingpage_events")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LandingpageEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_img_path", length = 500)
    private String eventImgPath;   // ảnh đầu tiên (backward compat)

    @Column(name = "event_label", length = 200)
    private String eventLabel;

    @Column(name = "created_at")
    private Long createdAt;

    /** Feature 6: JSON array nhiều ảnh ["url1","url2",...] */
    @Column(name = "image_urls", columnDefinition = "TEXT")
    private String imageUrls;
}
