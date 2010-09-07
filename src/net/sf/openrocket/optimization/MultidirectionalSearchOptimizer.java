package net.sf.openrocket.optimization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import net.sf.openrocket.logging.LogHelper;
import net.sf.openrocket.startup.Application;
import net.sf.openrocket.util.Statistics;

/**
 * A customized implementation of the parallel multidirectional search algorithm by Dennis and Torczon.
 * <p>
 * This is a parallel pattern search optimization algorithm.  The function evaluations are performed
 * using an ExecutorService.  By default a ThreadPoolExecutor is used that has as many thread defined
 * as the system has processors.
 */
public class MultidirectionalSearchOptimizer implements FunctionOptimizer, Statistics {
	private static final LogHelper log = Application.getLogger();
	
	private List<Point> simplex = new ArrayList<Point>();
	
	private ParallelFunctionCache functionExecutor;
	
	private boolean useExpansion = false;
	
	private int stepCount = 0;
	private int reflectionAcceptance = 0;
	private int expansionAcceptance = 0;
	private int coordinateAcceptance = 0;
	private int reductionFallback = 0;
	
	
	public MultidirectionalSearchOptimizer() {
		// No-op
	}
	
	public MultidirectionalSearchOptimizer(ParallelFunctionCache functionCache) {
		this.functionExecutor = functionCache;
	}
	
	

	@Override
	public void optimize(Point initial, OptimizationController control) {
		FunctionCacheComparator comparator = new FunctionCacheComparator(functionExecutor);
		
		final List<Point> pattern = SearchPattern.square(initial.dim());
		log.info("Starting optimization at " + initial + " with pattern " + pattern);
		
		try {
			
			boolean simplexComputed = false;
			double step = 0.5;
			
			// Set up the current simplex
			simplex.clear();
			simplex.add(initial);
			for (Point p : pattern) {
				simplex.add(initial.add(p.mul(step)));
			}
			
			// Normal iterations
			List<Point> reflection = new ArrayList<Point>(simplex.size());
			List<Point> expansion = new ArrayList<Point>(simplex.size());
			List<Point> coordinateSearch = new ArrayList<Point>(simplex.size());
			Point current;
			double currentValue;
			do {
				
				log.debug("Starting optimization step with simplex " + simplex +
						(simplexComputed ? "" : " (not computed)"));
				stepCount++;
				
				if (!simplexComputed) {
					// TODO: Could something be computed in parallel?
					functionExecutor.compute(simplex);
					functionExecutor.waitFor(simplex);
					Collections.sort(simplex, comparator);
					simplexComputed = true;
				}
				
				current = simplex.get(0);
				currentValue = functionExecutor.getValue(current);
				
				/*
				 * Compute and queue the next points in likely order of usefulness.
				 * Expansion is unlikely as we're mainly dealing with bounded optimization.
				 */
				createReflection(simplex, reflection);
				createCoordinateSearch(current, step, coordinateSearch);
				if (useExpansion)
					createExpansion(simplex, expansion);
				
				functionExecutor.compute(reflection);
				functionExecutor.compute(coordinateSearch);
				if (useExpansion)
					functionExecutor.compute(expansion);
				
				// Check reflection acceptance
				log.debug("Computing reflection");
				functionExecutor.waitFor(reflection);
				
				if (accept(reflection, currentValue)) {
					
					log.debug("Reflection was successful, aborting coordinate search, " +
							(useExpansion ? "computing" : "skipping") + " expansion");
					
					functionExecutor.abort(coordinateSearch);
					
					simplex.clear();
					simplex.add(current);
					simplex.addAll(reflection);
					Collections.sort(simplex, comparator);
					
					if (useExpansion) {
						
						/*
						 * Assume expansion to be unsuccessful, queue next reflection while computing expansion.
						 */
						createReflection(simplex, reflection);
						
						functionExecutor.compute(reflection);
						functionExecutor.waitFor(expansion);
						
						if (accept(expansion, currentValue)) {
							log.debug("Expansion was successful, aborting reflection");
							functionExecutor.abort(reflection);
							
							simplex.clear();
							simplex.add(current);
							simplex.addAll(expansion);
							step *= 2;
							Collections.sort(simplex, comparator);
							expansionAcceptance++;
						} else {
							log.debug("Expansion failed");
							reflectionAcceptance++;
						}
						
					} else {
						reflectionAcceptance++;
					}
					
				} else {
					
					log.debug("Reflection was unsuccessful, aborting expansion, computing coordinate search");
					
					functionExecutor.abort(expansion);
					
					/*
					 * Assume coordinate search to be unsuccessful, queue contraction step while computing.
					 */
					halveStep(simplex);
					functionExecutor.compute(simplex);
					functionExecutor.waitFor(coordinateSearch);
					
					if (accept(coordinateSearch, currentValue)) {
						
						log.debug("Coordinate search successful, reseting simplex");
						List<Point> toAbort = new LinkedList<Point>(simplex);
						simplex.clear();
						simplex.add(current);
						for (Point p : pattern) {
							simplex.add(current.add(p.mul(step)));
						}
						toAbort.removeAll(simplex);
						functionExecutor.abort(toAbort);
						simplexComputed = false;
						coordinateAcceptance++;
						
					} else {
						log.debug("Coordinate search unsuccessful, halving step.");
						step /= 2;
						reductionFallback++;
					}
					
				}
				
				log.debug("Ending optimization step with simplex " + simplex);
				
				if (Thread.interrupted()) {
					throw new InterruptedException();
				}
				
			} while (control.stepTaken(current, currentValue, simplex.get(0),
					functionExecutor.getValue(simplex.get(0)), step));
			
		} catch (InterruptedException e) {
			log.info("Optimization was interrupted with InterruptedException");
		}
		
		log.info("Finishing optimization at point " + simplex.get(0) + " value = " +
				functionExecutor.getValue(simplex.get(0)));
	}
	
	

	private void createReflection(List<Point> base, List<Point> reflection) {
		Point current = base.get(0);
		reflection.clear();
		for (int i = 1; i < base.size(); i++) {
			Point p = current.mul(2).sub(base.get(i));
			reflection.add(p);
		}
	}
	
	private void createExpansion(List<Point> base, List<Point> expansion) {
		Point current = base.get(0);
		expansion.clear();
		for (int i = 1; i < base.size(); i++) {
			Point p = current.mul(3).sub(base.get(i).mul(2));
			expansion.add(p);
		}
	}
	
	private void halveStep(List<Point> base) {
		Point current = base.get(0);
		for (int i = 1; i < base.size(); i++) {
			Point p = base.get(i);
			p = p.add(current).mul(0.5);
			base.set(i, p);
		}
	}
	
	private void createCoordinateSearch(Point current, double step, List<Point> coordinateDirections) {
		coordinateDirections.clear();
		for (int i = 0; i < current.dim(); i++) {
			Point p = new Point(current.dim());
			p = p.set(i, step);
			coordinateDirections.add(current.add(p));
			coordinateDirections.add(current.sub(p));
		}
	}
	
	
	private boolean accept(List<Point> points, double currentValue) {
		for (Point p : points) {
			if (functionExecutor.getValue(p) < currentValue) {
				return true;
			}
		}
		return false;
	}
	
	

	@Override
	public Point getOptimumPoint() {
		return simplex.get(0);
	}
	
	@Override
	public double getOptimumValue() {
		return functionExecutor.getValue(getOptimumPoint());
	}
	
	@Override
	public FunctionCache getFunctionCache() {
		return functionExecutor;
	}
	
	@Override
	public void setFunctionCache(FunctionCache functionCache) {
		if (!(functionCache instanceof ParallelFunctionCache)) {
			throw new IllegalArgumentException("Function cache needs to be a ParallelFunctionCache: " + functionCache);
		}
		this.functionExecutor = (ParallelFunctionCache) functionCache;
	}
	
	@Override
	public String getStatistics() {
		return "MultidirectionalSearchOptimizer[stepCount=" + stepCount +
				", reflectionAcceptance=" + reflectionAcceptance +
				", expansionAcceptance=" + expansionAcceptance +
				", coordinateAcceptance=" + coordinateAcceptance +
				", reductionFallback=" + reductionFallback;
	}
	
	@Override
	public void resetStatistics() {
		stepCount = 0;
		reflectionAcceptance = 0;
		expansionAcceptance = 0;
		coordinateAcceptance = 0;
		reductionFallback = 0;
	}
	
}