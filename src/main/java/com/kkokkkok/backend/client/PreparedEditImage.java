package com.kkokkkok.backend.client;

import java.util.List;
import java.util.Map;

record PreparedEditImage(byte[] imagePng, byte[] maskPng, int width, int height,
                         List<Map<String, Object>> visualCues) {}
