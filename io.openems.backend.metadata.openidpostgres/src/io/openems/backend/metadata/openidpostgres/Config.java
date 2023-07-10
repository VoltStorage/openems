package io.openems.backend.metadata.openidpostgres;

import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Metadata.OpenIdPostgres", //
		description = "Configures the Metadata OpenId Postgres provider")
@interface Config {

	String webconsole_configurationFactory_nameHint() default "Metadata OpenId Postgres";

}
