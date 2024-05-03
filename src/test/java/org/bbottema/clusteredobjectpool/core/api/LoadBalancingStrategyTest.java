package org.bbottema.clusteredobjectpool.core.api;

import org.bbottema.clusteredobjectpool.cyclingstrategies.RandomAccessLoadBalancing;
import org.bbottema.clusteredobjectpool.cyclingstrategies.RoundRobinLoadBalancing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.byLessThan;

public class LoadBalancingStrategyTest {
	@Test
	public void testRoundRobinLoadBalancingStrategy() {
		final RoundRobinLoadBalancing<Integer> balancer = new RoundRobinLoadBalancing<>();
		final Queue<Integer> collectionForCycling = balancer.createCollectionForCycling();
		
		collectionForCycling.add(1);
		collectionForCycling.add(2);
		collectionForCycling.add(3);
		
		assertThat(balancer.cycle(collectionForCycling)).isEqualTo(1);
		assertThat(balancer.cycle(collectionForCycling)).isEqualTo(2);
		assertThat(balancer.cycle(collectionForCycling)).isEqualTo(3);
		assertThat(balancer.cycle(collectionForCycling)).isEqualTo(1);
		assertThat(balancer.cycle(collectionForCycling)).isEqualTo(2);
		assertThat(balancer.cycle(collectionForCycling)).isEqualTo(3);
		assertThat(balancer.cycle(collectionForCycling)).isEqualTo(1);
		assertThat(balancer.cycle(collectionForCycling)).isEqualTo(2);
		assertThat(balancer.cycle(collectionForCycling)).isEqualTo(3);
	}
	
	@Test
	public void testRandomLoadBalancingStrategy() {
		final RandomAccessLoadBalancing<Double> balancer = new RandomAccessLoadBalancing<>();
		final List<Double> selectableUniqueNumbers = balancer.createCollectionForCycling();
		
		selectableUniqueNumbers.add(1d);
		selectableUniqueNumbers.add(2d);
		selectableUniqueNumbers.add(3d);
		selectableUniqueNumbers.add(4d);
		selectableUniqueNumbers.add(5d);
		selectableUniqueNumbers.add(6d);
		
		List<Double> randomlySelectedData = new ArrayList<>();
		for (int i = 0; i < 5_000_000; i++) {
			randomlySelectedData.add(balancer.cycle(selectableUniqueNumbers));
		}
		
		final double expectedStandardDeviation = calcStandardDeviation(selectableUniqueNumbers);
		final double actualStandardDeviation = calcStandardDeviation(randomlySelectedData);
		
		assertThat(expectedStandardDeviation).isCloseTo(actualStandardDeviation, byLessThan(.001));
	}
	
	private static double calcStandardDeviation(Collection<Double> data) {
		double meanAvg = 0.0;
		for (double datum : data) {
			meanAvg += datum;
		}
		meanAvg /= data.size();
		
		double variance = 0;
		for (double datum : data) {
			variance += (datum - meanAvg) * (datum - meanAvg);
		}
		variance /= data.size();
		
		// standard Deviation
		return Math.sqrt(variance);
	}
}