package io.openems.edge.app.timeofusetariff;

import java.util.Map;
import java.util.function.Function;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Reference;

import com.google.common.collect.Lists;
import com.google.gson.JsonElement;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingTriFunction;
import io.openems.common.session.Language;
import io.openems.common.types.EdgeConfig;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.app.common.props.CommonProps;
import io.openems.edge.app.timeofusetariff.AwattarHourly.Property;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.core.appmanager.AbstractOpenemsApp;
import io.openems.edge.core.appmanager.AbstractOpenemsAppWithProps;
import io.openems.edge.core.appmanager.AppConfiguration;
import io.openems.edge.core.appmanager.AppDef;
import io.openems.edge.core.appmanager.AppDescriptor;
import io.openems.edge.core.appmanager.ComponentUtil;
import io.openems.edge.core.appmanager.ConfigurationTarget;
import io.openems.edge.core.appmanager.Nameable;
import io.openems.edge.core.appmanager.OpenemsApp;
import io.openems.edge.core.appmanager.OpenemsAppCardinality;
import io.openems.edge.core.appmanager.OpenemsAppCategory;
import io.openems.edge.core.appmanager.Type;
import io.openems.edge.core.appmanager.Type.Parameter.BundleParameter;
import io.openems.edge.core.appmanager.dependency.Tasks;

/**
 * Describes a App for AwattarHourly.
 *
 * <pre>
  {
    "appId":"App.TimeOfUseTariff.Awattar",
    "alias":"Awattar HOURLY",
    "instanceId": UUID,
    "image": base64,
    "properties":{
    	"CTRL_ESS_TIME_OF_USE_TARIFF_ID": "ctrlEssTimeOfUseTariff0",
    	"TIME_OF_USE_TARIFF_PROVIDER_ID": "timeOfUseTariff0",
    	"CONTROL_MODE": {@link ControlMode}
    },
    "appDescriptor": {
    	"websiteUrl": {@link AppDescriptor#getWebsiteUrl()}
    }
  }
 * </pre>
 */
@org.osgi.service.component.annotations.Component(name = "App.TimeOfUseTariff.Awattar")
public class AwattarHourly extends AbstractOpenemsAppWithProps<AwattarHourly, Property, Type.Parameter.BundleParameter>
		implements OpenemsApp {

	public static enum Property implements Type<Property, AwattarHourly, Type.Parameter.BundleParameter>, Nameable {
		// Components
		CTRL_ESS_TIME_OF_USE_TARIFF_ID(AppDef.componentId("ctrlEssTimeOfUseTariff0")), //
		TIME_OF_USE_TARIFF_PROVIDER_ID(AppDef.componentId("timeOfUseTariff0")), //

		// Properties
		ALIAS(CommonProps.alias());

		private final AppDef<? super AwattarHourly, ? super Property, ? super Type.Parameter.BundleParameter> def;

		private Property(AppDef<? super AwattarHourly, ? super Property, ? super Type.Parameter.BundleParameter> def) {
			this.def = def;
		}

		@Override
		public Property self() {
			return this;
		}

		@Override
		public AppDef<? super AwattarHourly, ? super Property, ? super BundleParameter> def() {
			return this.def;
		}

		@Override
		public Function<GetParameterValues<AwattarHourly>, BundleParameter> getParamter() {
			return Type.Parameter.functionOf(AbstractOpenemsApp::getTranslationBundle);
		}
	}

	@Activate
	public AwattarHourly(@Reference ComponentManager componentManager, ComponentContext context,
			@Reference ConfigurationAdmin cm, @Reference ComponentUtil componentUtil) {
		super(componentManager, context, cm, componentUtil);
	}

	@Override
	protected ThrowingTriFunction<ConfigurationTarget, Map<Property, JsonElement>, Language, AppConfiguration, OpenemsNamedException> appPropertyConfigurationFactory() {
		return (t, p, l) -> {
			final var timeOfUseTariffProviderId = this.getId(t, p, Property.TIME_OF_USE_TARIFF_PROVIDER_ID);
			final var ctrlEssTimeOfUseTariffId = this.getId(t, p, Property.CTRL_ESS_TIME_OF_USE_TARIFF_ID);

			final var alias = this.getString(p, l, Property.ALIAS);

			var components = Lists.newArrayList(//
					new EdgeConfig.Component(ctrlEssTimeOfUseTariffId, alias, "Controller.Ess.Time-Of-Use-Tariff",
							JsonUtils.buildJsonObject() //
									.addProperty("ess.id", "ess0") //
									.build()), //
					new EdgeConfig.Component(timeOfUseTariffProviderId, this.getName(l), "TimeOfUseTariff.Awattar",
							JsonUtils.buildJsonObject() //
									.build())//
			);

			return AppConfiguration.create() //
					.addTask(Tasks.component(components)) //
					.addTask(Tasks.scheduler(ctrlEssTimeOfUseTariffId, "ctrlBalancing0")) //
					.addTask(Tasks.persistencePredictor("_sum/UnmanagedConsumptionActivePower")) //
					.build();
		};
	}

	@Override
	public AppDescriptor getAppDescriptor() {
		return AppDescriptor.create() //
				.build();
	}

	@Override
	public OpenemsAppCategory[] getCategories() {
		return new OpenemsAppCategory[] { OpenemsAppCategory.TIME_OF_USE_TARIFF };
	}

	@Override
	protected Property[] propertyValues() {
		return Property.values();
	}

	@Override
	public OpenemsAppCardinality getCardinality() {
		return OpenemsAppCardinality.SINGLE_IN_CATEGORY;
	}

	@Override
	protected AwattarHourly getApp() {
		return this;
	}

}
