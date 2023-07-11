import { NgModule } from '@angular/core';
import {OpenIdComponent} from "./openid.component";
import {AsyncPipe, NgIf} from "@angular/common";

@NgModule({
  imports: [
    AsyncPipe,
    NgIf
  ],
  declarations: [
    OpenIdComponent,
  ]
})
export class OpenIdModule { }
