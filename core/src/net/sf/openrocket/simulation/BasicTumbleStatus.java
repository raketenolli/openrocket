package net.sf.openrocket.simulation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

import net.sf.openrocket.rocketcomponent.FinSet;
import net.sf.openrocket.rocketcomponent.FlightConfiguration;
import net.sf.openrocket.rocketcomponent.InstanceContext;
import net.sf.openrocket.rocketcomponent.InstanceMap;
import net.sf.openrocket.rocketcomponent.Rocket;
import net.sf.openrocket.rocketcomponent.RocketComponent;
import net.sf.openrocket.rocketcomponent.SymmetricComponent;

public class BasicTumbleStatus extends SimulationStatus {
	
	// Magic constants from techdoc.pdf
	private final static double cDFin = 1.42;
	private final static double cDBt = 0.56;
	// Fin efficiency.  Index is number of fins.  The 0th entry is arbitrary and used to
	// offset the indexes so finEff[1] is the coefficient for one fin from the table in techdoc.pdf
	private final static double[] finEff = { 0.0, 0.5, 1.0, 1.41, 1.81, 1.73, 1.90, 1.85 };
	
	private final double drag;
	
	public BasicTumbleStatus(FlightConfiguration configuration,
			SimulationConditions simulationConditions) {
		super(configuration, simulationConditions);
		this.drag = computeTumbleDrag();
	}
	
	public BasicTumbleStatus(SimulationStatus orig) {
		super(orig);
		if (orig instanceof BasicTumbleStatus) {
			this.drag = ((BasicTumbleStatus) orig).drag;
		} else {
			this.drag = computeTumbleDrag();
		}
	}
	
	public double getTumbleDrag() {
		return drag;
	}
	
	
	private double computeTumbleDrag() {
		
		// Computed based on Sampo's experimentation as documented in the pdf.
		
		// compute the fin and body tube projected areas
		double aFins = 0.0;
		double aBt = 0.0;
		final InstanceMap imap = this.getConfiguration().getActiveInstances();
	    for(Map.Entry<RocketComponent, ArrayList<InstanceContext>> entry: imap.entrySet() ) {
			final RocketComponent component = entry.getKey();
			
			if (!component.isAerodynamic()) {
				continue;
			}
			
			// iterate across component instances
			final ArrayList<InstanceContext> contextList = entry.getValue();
			for(InstanceContext context: contextList ) {
				
				if (component instanceof FinSet) {
					final FinSet finComponent = ((FinSet) component);
					final double finArea = finComponent.getPlanformArea();
					int finCount = finComponent.getFinCount();
					
					// check bounds on finCount.
					if (finCount >= finEff.length) {
						finCount = finEff.length - 1;
					}
					
					aFins += finArea * finEff[finCount] / finComponent.getFinCount();
					
				} else if (component instanceof SymmetricComponent) {
					aBt += ((SymmetricComponent) component).getComponentPlanformArea();
				}
			}
		}
		
		return (cDFin * aFins + cDBt * aBt);
	}
}
