import {Component, OnInit} from "@angular/core";
import {KeycloakService} from "keycloak-angular";
import {CookieService} from "ngx-cookie-service";
import {Router} from "@angular/router";
import {Environment, environment} from 'src/environments';
import {AuthService} from "@auth0/auth0-angular";
import {MsalService} from "@azure/msal-angular";
import {AuthenticationResult} from "@azure/msal-browser";

@Component({
  selector: 'openid',
  template: `
    <h1>OpenId Return Page</h1>
    <h2>IAM provider: ${environment.authProvider}</h2>
    <ng-container *ngIf="environment.authProvider === 'auth0'">
      <button (click)="login()">Login</button>
      <button (click)="logout()">Logout</button>
      <ul *ngIf="auth0Service.user$ | async as user">
        <li>{{ user.name }}</li>
        <li>{{ user.email }}</li>
      </ul>
    </ng-container>
    <h2></h2>
    <ng-container *ngIf="environment.authProvider === 'entra'">
      <button (click)="logoutEntra()">Logout</button>
      <button (click)="loginEntra()">Login</button>
    </ng-container>
  `
})
export class OpenIdComponent implements OnInit {

  readonly environment: Environment

  constructor(
    private cookieService: CookieService,
    private keycloakService: KeycloakService,
    public auth0Service: AuthService,
    private msalService: MsalService,
    private router: Router
  ) {
    this.environment = environment;
  }

  logoutEntra() {
    console.info("Logout Entra");
    this.msalService.logout();
  }

  loginEntra() {
    console.info("Login Entra");
    this.msalService.loginRedirect();
  }

  logout() {
    console.info("Logout");
    this.auth0Service.logout();
    this.cookieService.delete("token");
  }

  login() {
    const redirectUri = `${window.location.origin}/openid-return`;
    console.info("loginWithRedirect to target", redirectUri)
    this.auth0Service.loginWithRedirect();
  }

  ngOnInit(): void {

    if (environment.authProvider === "keycloak") {
      const keycloakInstance = this.keycloakService.getKeycloakInstance();
      const accessToken = keycloakInstance.token;

      if (accessToken) {
        console.info("Keycloak: accessToken found", accessToken)
        this.cookieService.set("token", accessToken);

      } else {
        console.info("Keycloak: Token was undefined")
        this.cookieService.delete("token");
      }

      this.router.navigate(['index']).then(r => console.info(r));
    }

    if (environment.authProvider === "auth0") {
      //this is handled in websocket.ts
    }

    if (environment.authProvider === "entra") {
      console.info("Initializing Entra provider");

      const allAccounts = this.msalService.instance.getAllAccounts();
      console.info("Msal: allAccounts", allAccounts);

      const activeAccount = this.msalService.instance.getActiveAccount();
      console.info("Msal: ActiveAccount", activeAccount);

      this.msalService.acquireTokenSilent({
        scopes: ["User.Read"],
      }).subscribe({
        next: (result: AuthenticationResult) => {
          console.info("entra acquireTokenSilent result", result)
        },
        error: (error) => {
          console.info("entra acquireTokenSilent not successful; redirecting to msal login.", error)
          //this.msalService.loginRedirect(); // Handle error by logging in interactively
        }
      });

    }

  }
}
