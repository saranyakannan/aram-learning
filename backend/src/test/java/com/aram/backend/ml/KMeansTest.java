package com.aram.backend.ml;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KMeansTest {

	private static final double[][] TWO_GROUPS = {
			{1, 0.1, 0}, {0.9, 0.0, 0.1}, {1, 0, 0}, {0.95, 0.05, 0},
			{0, 1, 0.1}, {0.1, 0.9, 0}, {0, 1, 0}, {0, 0.95, 0.05}};

	@Test
	void separatesTwoObviousGroups() {
		int[] result = KMeans.cluster(TWO_GROUPS, 2, 42);

		assertThat(result[0]).isEqualTo(result[1]).isEqualTo(result[2]).isEqualTo(result[3]);
		assertThat(result[4]).isEqualTo(result[5]).isEqualTo(result[6]).isEqualTo(result[7]);
		assertThat(result[0]).isNotEqualTo(result[4]);
	}

	@Test
	void sameInputAndSeedAlwaysGiveTheSameClusters() {
		assertThat(KMeans.cluster(TWO_GROUPS, 3, 7)).containsExactly(KMeans.cluster(TWO_GROUPS, 3, 7));
	}

	@Test
	void askingForMoreClustersThanPointsOrNoPointsIsHandled() {
		assertThat(KMeans.cluster(new double[0][], 4, 1)).isEmpty();
		assertThat(KMeans.cluster(new double[][]{{1, 0}, {0, 1}}, 10, 1)).hasSize(2);
		assertThat(KMeans.cluster(new double[][]{{0, 0}, {0, 0}}, 2, 1)).hasSize(2);
	}
}
