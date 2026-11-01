import { info } from "nuke:log";
import { get } from "nuke:config";

info(`hello ${get("greeting")}`);
