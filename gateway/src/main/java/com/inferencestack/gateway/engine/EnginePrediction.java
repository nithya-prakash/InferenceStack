package com.inferencestack.gateway.engine;

public record EnginePrediction(double anomalyScore, boolean anomalous, double threshold, String modelVersion, double inferenceMs) {}
