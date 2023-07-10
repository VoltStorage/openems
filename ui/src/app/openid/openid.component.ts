import {Component, OnInit} from "@angular/core";
import {KeycloakService} from "keycloak-angular";
import {CookieService} from "ngx-cookie-service";
import {Router} from "@angular/router";

@Component({
  selector: 'openid',
  template: `<p>OpenId Return Page</p>`
})
export class OpenIdComponent implements OnInit {


  constructor(
    private cookieService: CookieService,
    private keycloakService: KeycloakService,
    private router: Router
  ) {

  }

  ngOnInit(): void {
    const keycloakInstance = this.keycloakService.getKeycloakInstance();
    const accessToken = keycloakInstance.token;

    if (accessToken) {
      console.info("accessToken found")
      this.cookieService.set("token", accessToken);

    } else {
      console.info("Token was undefined")
      this.cookieService.delete("token");
    }

    this.router.navigate(['index']).then(r => console.info(r));
  }

}
