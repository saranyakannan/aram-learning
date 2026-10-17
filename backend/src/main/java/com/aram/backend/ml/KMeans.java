package com.aram.backend.ml;

import java.util.Random;

/**
 * Deterministic spherical k-means (cosine geometry) with k-means++ seeding, used to group source embeddings into topic
 * clusters. Done here rather than in BigQuery ML because the BigQuery docs do not confirm that KMEANS accepts an
 * ARRAY feature column (decision record 0008, amended); at hundreds of sources this is instant and free.
 */
final class KMeans {

	private static final int MAX_ITERATIONS = 100;

	private KMeans() {
	}

	/** Returns the cluster index (0..k-1) of each vector. Same input and seed always give the same answer. */
	static int[] cluster(double[][] vectors, int k, long seed) {
		int n = vectors.length;
		if (n == 0) {
			return new int[0];
		}
		int clusters = Math.max(1, Math.min(k, n));
		double[][] points = new double[n][];
		for (int i = 0; i < n; i++) {
			points[i] = normalise(vectors[i]);
		}
		Random random = new Random(seed);
		double[][] centroids = seed(points, clusters, random);
		int[] assignment = new int[n];
		java.util.Arrays.fill(assignment, -1);
		for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
			boolean changed = false;
			for (int i = 0; i < n; i++) {
				int best = nearest(points[i], centroids);
				if (best != assignment[i]) {
					assignment[i] = best;
					changed = true;
				}
			}
			if (!changed) {
				break;
			}
			int dimensions = points[0].length;
			double[][] sums = new double[clusters][dimensions];
			int[] counts = new int[clusters];
			for (int i = 0; i < n; i++) {
				counts[assignment[i]]++;
				for (int d = 0; d < dimensions; d++) {
					sums[assignment[i]][d] += points[i][d];
				}
			}
			for (int c = 0; c < clusters; c++) {
				if (counts[c] > 0) {
					centroids[c] = normalise(sums[c]);
				}
			}
		}
		return assignment;
	}

	private static double[][] seed(double[][] points, int clusters, Random random) {
		double[][] centroids = new double[clusters][];
		centroids[0] = points[random.nextInt(points.length)].clone();
		for (int c = 1; c < clusters; c++) {
			double[] distance = new double[points.length];
			double total = 0;
			for (int i = 0; i < points.length; i++) {
				double best = Double.MAX_VALUE;
				for (int j = 0; j < c; j++) {
					best = Math.min(best, 1 - dot(points[i], centroids[j]));
				}
				distance[i] = Math.max(0, best);
				total += distance[i];
			}
			int chosen = 0;
			if (total > 0) {
				double target = random.nextDouble() * total;
				double running = 0;
				for (int i = 0; i < points.length; i++) {
					running += distance[i];
					if (running >= target) {
						chosen = i;
						break;
					}
				}
			} else {
				chosen = random.nextInt(points.length);
			}
			centroids[c] = points[chosen].clone();
		}
		return centroids;
	}

	private static int nearest(double[] point, double[][] centroids) {
		int best = 0;
		double bestSimilarity = -Double.MAX_VALUE;
		for (int c = 0; c < centroids.length; c++) {
			double similarity = dot(point, centroids[c]);
			if (similarity > bestSimilarity) {
				bestSimilarity = similarity;
				best = c;
			}
		}
		return best;
	}

	private static double dot(double[] a, double[] b) {
		double sum = 0;
		for (int i = 0; i < a.length; i++) {
			sum += a[i] * b[i];
		}
		return sum;
	}

	private static double[] normalise(double[] vector) {
		double norm = Math.sqrt(dot(vector, vector));
		double[] result = new double[vector.length];
		if (norm == 0) {
			return result;
		}
		for (int i = 0; i < vector.length; i++) {
			result[i] = vector[i] / norm;
		}
		return result;
	}
}
