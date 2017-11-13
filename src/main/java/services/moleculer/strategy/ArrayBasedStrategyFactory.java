/**
 * This software is licensed under MIT license.<br>
 * <br>
 * Copyright 2017 Andras Berkes [andras.berkes@programmer.net]<br>
 * <br>
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:<br>
 * <br>
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.<br>
 * <br>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package services.moleculer.strategy;

import io.datatree.Tree;
import services.moleculer.ServiceBroker;

/**
 * Abstract class for Round-Robin and Random invocation strategy factories.
 * 
 * @see RoundRobinStrategyFactory
 * @see NanoSecRandomStrategyFactory
 * @see SecureRandomStrategyFactory
 * @see XORShiftRandomStrategyFactory
 */
public abstract class ArrayBasedStrategyFactory extends StrategyFactory {

	// --- PROPERTIES ---
	
	/**
	 * Invoke local actions if possible
	 */
	protected boolean preferLocal;
	
	// --- CONSTRUCTORS ---
		
	public ArrayBasedStrategyFactory() {
		this(true);
	}
	
	public ArrayBasedStrategyFactory(boolean preferLocal) {
		this.preferLocal = preferLocal;
	}
	
	// --- START INVOCATION FACTORY ---

	/**
	 * Initializes strategy instance.
	 * 
	 * @param broker
	 *            parent ServiceBroker
	 * @param config
	 *            optional configuration of the current component
	 */
	@Override
	public void start(ServiceBroker broker, Tree config) throws Exception {
		
		// Process config
		preferLocal = config.get(PREFER_LOCAL, preferLocal);
	}

	// --- GETTERS / SETTERS ---
	
	public final boolean isPreferLocal() {
		return preferLocal;
	}

	public final void setPreferLocal(boolean preferLocal) {
		this.preferLocal = preferLocal;
	}
	
}