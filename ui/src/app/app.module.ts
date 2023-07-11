import {registerLocaleData} from '@angular/common';
import {HttpClientModule} from '@angular/common/http';
import localDE from '@angular/common/locales/de';
import {APP_INITIALIZER, ErrorHandler, LOCALE_ID, NgModule} from '@angular/core';
import {BrowserModule} from '@angular/platform-browser';
import {BrowserAnimationsModule} from '@angular/platform-browser/animations';
import {RouteReuseStrategy} from '@angular/router';
import {ServiceWorkerModule} from '@angular/service-worker';
import {IonicModule, IonicRouteStrategy} from '@ionic/angular';
import {FORMLY_CONFIG} from '@ngx-formly/core';
import {TranslateLoader, TranslateModule, TranslateService} from '@ngx-translate/core';
import {AngularMyDatePickerModule} from 'angular-mydatepicker';
import {CookieService} from 'ngx-cookie-service';
import {environment} from 'src/environments';

import {AppRoutingModule} from './app-routing.module';
import {AppComponent} from './app.component';
import {CheckForUpdateService} from './appupdateservice';
import {ChangelogModule} from './changelog/changelog.module';
import {EdgeModule} from './edge/edge.module';
import {SettingsModule as EdgeSettingsModule} from './edge/settings/settings.module';
import {SystemLogComponent} from './edge/settings/systemlog/systemlog.component';
import {IndexModule} from './index/index.module';
import {RegistrationModule} from './registration/registration.module';
import {ChartOptionsPopoverComponent} from './shared/chartoptions/popover/popover.component';
import {PickDatePopoverComponent} from './shared/pickdate/popover/popover.component';
import {MyErrorHandler} from './shared/service/myerrorhandler';
import {Pagination} from './shared/service/pagination';
import {SharedModule} from './shared/shared.module';
import {StatusSingleComponent} from './shared/status/single/status.component';
import {registerTranslateExtension} from './shared/translate.extension';
import {Language, MyTranslateLoader} from './shared/type/language';
import {UserModule} from './user/user.module';
import {KeycloakAngularModule, KeycloakService} from "keycloak-angular";
import {OpenIdModule} from "./openid/openid.module";
import {AuthModule} from "@auth0/auth0-angular";

function initializeKeycloak(keycloak: KeycloakService) {
  return () =>
    keycloak.init({
      config: {
        url: 'http://localhost:8080',
        realm: 'voltstorage-customers',
        clientId: 'openems'
      },
      initOptions: {
        checkLoginIframe: false
      }
    });
}

@NgModule({
  declarations: [
    AppComponent,
    ChartOptionsPopoverComponent,
    PickDatePopoverComponent,
    StatusSingleComponent,
    SystemLogComponent,
  ],
  entryComponents: [
    ChartOptionsPopoverComponent,
    PickDatePopoverComponent,
  ],
  imports: [
    AngularMyDatePickerModule,
    AppRoutingModule,
    BrowserAnimationsModule,
    BrowserModule,
    ChangelogModule,
    EdgeModule,
    EdgeSettingsModule,
    IndexModule,
    IonicModule.forRoot(),
    AuthModule.forRoot({
      domain: 'dev-dripbmlnz2xh60k8.eu.auth0.com',
      clientId: 'kaUPrEUDC93nfRoiLoaww62RxYHfI6Ga',
      authorizationParams: {
        audience: 'https://openems.volt.cloud',
        redirect_uri: window.location.origin + '/openid-return'
      }
    }),
    KeycloakAngularModule,
    HttpClientModule,
    OpenIdModule,
    SharedModule,
    TranslateModule.forRoot({loader: {provide: TranslateLoader, useClass: MyTranslateLoader}}),
    UserModule,
    RegistrationModule,
    ServiceWorkerModule.register('/ngsw-worker.js', {enabled: environment.production})
  ],
  providers: [
    {provide: RouteReuseStrategy, useClass: IonicRouteStrategy},
    CookieService,
    {provide: ErrorHandler, useClass: MyErrorHandler},
    {provide: LOCALE_ID, useValue: Language.DEFAULT.key},
    // Use factory for formly. This allows us to use translations in validationMessages.
    {provide: FORMLY_CONFIG, multi: true, useFactory: registerTranslateExtension, deps: [TranslateService]},
    Pagination,
    CheckForUpdateService,
    {
      provide: APP_INITIALIZER,
      useFactory: initializeKeycloak,
      multi: true,
      deps: [KeycloakService]
    }
  ],
  bootstrap: [AppComponent],
})
export class AppModule {
  constructor() {
    registerLocaleData(localDE);
  }
}
