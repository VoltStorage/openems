import {Component, OnInit} from "@angular/core";
import {KeycloakService} from "keycloak-angular";
import {CookieService} from "ngx-cookie-service";
import {Router} from "@angular/router";
import {Environment, environment} from 'src/environments';
import {AuthService} from "@auth0/auth0-angular";

@Component({
  selector: 'openid',
  template: `
    <h1>OpenId Return Page</h1>
    <ng-container *ngIf="environment.authProvider === 'auth0'">
      <button (click)="login()">Login</button>
      <button (click)="logout()">Logout</button>
      <ul *ngIf="auth0Service.user$ | async as user">
        <li>{{ user.name }}</li>
        <li>{{ user.email }}</li>
      </ul>
    </ng-container>
  `
})
export class OpenIdComponent implements OnInit {

  readonly environment: Environment

  constructor(
    private cookieService: CookieService,
    private keycloakService: KeycloakService,
    public auth0Service: AuthService,
    private router: Router
  ) {
    this.environment = environment;
  }

  logout(){
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

  }

}
