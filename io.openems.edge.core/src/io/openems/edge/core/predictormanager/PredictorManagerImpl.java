package io.openems.edge.core.predictormanager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.jsonrpc.base.JsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcResponseSuccess;
import io.openems.common.types.ChannelAddress;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.sum.Sum;
import io.openems.edge.common.user.User;
import io.openems.edge.ess.dccharger.api.EssDcCharger;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.predictor.api.manager.PredictorManager;
import io.openems.edge.predictor.api.oneday.Prediction24Hours;
import io.openems.edge.predictor.api.oneday.Predictor24Hours;

@Designate(ocd = Config.class, factory = false)
@Component(//
		name = PredictorManager.SINGLETON_SERVICE_PID, //
		immediate = true, //
		property = { //
				"enabled=true" //
		})
public class PredictorManagerImpl extends AbstractOpenemsComponent
		implements PredictorManager, OpenemsComponent, JsonApi {

	private final Logger log = LoggerFactory.getLogger(PredictorManagerImpl.class);

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private ComponentManager componentManager;

	@Reference(policy = ReferencePolicy.DYNAMIC, //
			policyOption = ReferencePolicyOption.GREEDY, //
			cardinality = ReferenceCardinality.MULTIPLE, //
			target = "(enabled=true)")
	private volatile List<Predictor24Hours> predictors = new CopyOnWriteArrayList<>();

	public PredictorManagerImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				PredictorManager.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context) {
		super.activate(context, PredictorManager.SINGLETON_COMPONENT_ID, SINGLETON_SERVICE_PID, true);

		if (OpenemsComponent.validateSingleton(this.cm, SINGLETON_SERVICE_PID, SINGLETON_COMPONENT_ID)) {
			return;
		}
	}

	@Modified
	private void modified(ComponentContext context, Config config) throws OpenemsNamedException {
		super.modified(context, SINGLETON_COMPONENT_ID, SINGLETON_SERVICE_PID, true);

		if (OpenemsComponent.validateSingleton(this.cm, SINGLETON_SERVICE_PID, SINGLETON_COMPONENT_ID)) {
			return;
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public CompletableFuture<? extends JsonrpcResponseSuccess> handleJsonrpcRequest(User user, JsonrpcRequest request)
			throws OpenemsNamedException {
		switch (request.getMethod()) {
		case Get24HoursPredictionRequest.METHOD:
			return this.handleGet24HoursPredictionRequest(user, Get24HoursPredictionRequest.from(request));
		}
		return null;
	}

	private CompletableFuture<? extends JsonrpcResponseSuccess> handleGet24HoursPredictionRequest(User user,
			Get24HoursPredictionRequest request) throws OpenemsNamedException {
		final Map<ChannelAddress, Prediction24Hours> predictions = new HashMap<>();
		for (ChannelAddress channel : request.getChannels()) {
			predictions.put(channel, this.get24HoursPrediction(channel));
		}
		return CompletableFuture.completedFuture(new Get24HoursPredictionResponse(request.getId(), predictions));
	}

	@Override
	public Prediction24Hours get24HoursPrediction(ChannelAddress channelAddress) {
		var predictor = this.getPredictorBestMatch(channelAddress);
		if (predictor != null) {
			return predictor.get24HoursPrediction(channelAddress);
		}
		// No explicit predictor found
		if (channelAddress.getComponentId().equals(Sum.SINGLETON_COMPONENT_ID)) {
			// This is a Sum-Channel. Try to get predictions for each source channel.
			try {
				return this.getPredictionSum(Sum.ChannelId.valueOf(
						io.openems.edge.common.channel.ChannelId.channelIdCamelToUpper(channelAddress.getChannelId())));

			} catch (IllegalArgumentException e) {
				this.logWarn(this.log, "Unable to find ChannelId for " + channelAddress);
				return Prediction24Hours.EMPTY;
			}

		} else {
			return Prediction24Hours.EMPTY;
		}
	}

	/**
	 * Gets the {@link Prediction24Hours} for a Sum-Channel.
	 *
	 * @param channelId the {@link Sum.ChannelId}
	 * @return the {@link Prediction24Hours}
	 */
	private Prediction24Hours getPredictionSum(Sum.ChannelId channelId) {
		return switch (channelId) {
		case CONSUMPTION_ACTIVE_ENERGY, //
				CONSUMPTION_ACTIVE_POWER_L1, CONSUMPTION_ACTIVE_POWER_L2, CONSUMPTION_ACTIVE_POWER_L3,
				CONSUMPTION_MAX_ACTIVE_POWER, //

				ESS_ACTIVE_CHARGE_ENERGY, ESS_ACTIVE_DISCHARGE_ENERGY, ESS_ACTIVE_POWER, ESS_ACTIVE_POWER_L1,
				ESS_ACTIVE_POWER_L2, ESS_ACTIVE_POWER_L3, ESS_CAPACITY, ESS_DC_CHARGE_ENERGY, ESS_DC_DISCHARGE_ENERGY,
				ESS_DISCHARGE_POWER, ESS_MAX_APPARENT_POWER, ESS_REACTIVE_POWER, ESS_SOC, //

				GRID_ACTIVE_POWER, GRID_ACTIVE_POWER_L1, GRID_ACTIVE_POWER_L2, GRID_ACTIVE_POWER_L3,
				GRID_BUY_ACTIVE_ENERGY, GRID_MAX_ACTIVE_POWER, GRID_MIN_ACTIVE_POWER, GRID_MODE,
				GRID_SELL_ACTIVE_ENERGY, //

				PRODUCTION_ACTIVE_ENERGY, PRODUCTION_AC_ACTIVE_ENERGY, PRODUCTION_AC_ACTIVE_POWER_L1,
				PRODUCTION_AC_ACTIVE_POWER_L2, PRODUCTION_AC_ACTIVE_POWER_L3, PRODUCTION_DC_ACTIVE_ENERGY,
				PRODUCTION_MAX_ACTIVE_POWER, PRODUCTION_MAX_AC_ACTIVE_POWER, PRODUCTION_MAX_DC_ACTUAL_POWER, //

				HAS_IGNORED_COMPONENT_STATES ->
			Prediction24Hours.EMPTY;

		case UNMANAGED_CONSUMPTION_ACTIVE_POWER ->
			// Fallback for elder systems that only provide predictors for
			// ConsumptionActivePower by default
			this.get24HoursPrediction(new ChannelAddress("_sum", "ConsumptionActivePower"));

		// TODO
		case CONSUMPTION_ACTIVE_POWER -> Prediction24Hours.EMPTY;

		case PRODUCTION_DC_ACTUAL_POWER -> {
			// Sum up "ActualPower" prediction of all EssDcChargers
			List<EssDcCharger> chargers = this.componentManager.getEnabledComponentsOfType(EssDcCharger.class);
			var predictions = new Prediction24Hours[chargers.size()];
			for (var i = 0; i < chargers.size(); i++) {
				var charger = chargers.get(i);
				predictions[i] = this.get24HoursPrediction(
						new ChannelAddress(charger.id(), EssDcCharger.ChannelId.ACTUAL_POWER.id()));
			}
			yield Prediction24Hours.sum(predictions);
		}

		case PRODUCTION_AC_ACTIVE_POWER -> {
			// Sum up "ActivePower" prediction of all ElectricityMeter
			List<ElectricityMeter> meters = this.componentManager.getEnabledComponentsOfType(ElectricityMeter.class)
					.stream() //
					.filter(meter -> {
						switch (meter.getMeterType()) {
						case GRID:
						case CONSUMPTION_METERED:
						case CONSUMPTION_NOT_METERED:
							return false;
						case PRODUCTION:
						case PRODUCTION_AND_CONSUMPTION:
							// Get only Production meters
							return true;
						}
						// should never come here
						return false;
					}).toList();
			var predictions = new Prediction24Hours[meters.size()];
			for (var i = 0; i < meters.size(); i++) {
				var meter = meters.get(i);
				predictions[i] = this.get24HoursPrediction(
						new ChannelAddress(meter.id(), ElectricityMeter.ChannelId.ACTIVE_POWER.id()));
			}
			yield Prediction24Hours.sum(predictions);
		}

		case PRODUCTION_ACTIVE_POWER -> Prediction24Hours.sum(//
				this.getPredictionSum(Sum.ChannelId.PRODUCTION_AC_ACTIVE_POWER), //
				this.getPredictionSum(Sum.ChannelId.PRODUCTION_DC_ACTUAL_POWER) //
			);
		};
	}

	/**
	 * Gets the best matching {@link Predictor24Hours} for the given
	 * {@link ChannelAddress}.
	 *
	 * @param channelAddress the {@link ChannelAddress}
	 * @return the {@link Predictor24Hours} - or null if none matches
	 */
	private synchronized Predictor24Hours getPredictorBestMatch(ChannelAddress channelAddress) {
		var bestMatchValue = -1;
		Predictor24Hours bestPredictor = null;
		for (Predictor24Hours predictor : this.predictors) {
			for (ChannelAddress pattern : predictor.getChannelAddresses()) {
				var matchValue = ChannelAddress.match(channelAddress, pattern);
				if (matchValue == 0) {
					// Exact match
					return predictor;
				}
				if (matchValue > bestMatchValue) {
					bestMatchValue = matchValue;
					bestPredictor = predictor;
				}
			}
		}
		return bestPredictor;
	}

}
