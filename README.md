[![Build Status](https://travis-ci.org/dtreskunov/wta-wdc.svg?branch=master)](https://travis-ci.org/dtreskunov/wta-wdc)
[![Language](https://img.shields.io/badge/language-clojure-brightgreen.svg)]()

# WTA Tableau Web Data Connector

Are you a data nerd AND hiker or a biker? You've come to the right place! Tableau is data visualization software that lets you see and understand data in minutes. Web Data Connector allows you to connect web data to Tableau. This one pulls data from popular trail guides such as [WTA](http://www.wta.org) so you can geek out before heading out to your next outdoors adventure.

## Setup For Users

Copy-and-paste the link below into Tableau Desktop's Web Data Connector dialog:

**[WTA Connector Link](https://dtreskunov.github.io/wta-wdc/)**

## Setup For Developers

Refer to Tableau Web Data Connector [documentation hub](http://tableau.github.io/webdataconnector/) for WDC specifics.

To get an interactive development environment run:

    lein figwheel

and open your browser at [localhost:3449](http://localhost:3449/).
This will auto compile and send all changes to the browser without the
need to reload. After the compilation process is complete, you will
get a Browser Connected REPL. An easy way to try it is:

    (js/alert "Am I connected?")

and you should see an alert in the browser window.

To clean all compiled files:

    lein clean

To create a production build run:

    lein do clean, cljsbuild once min

And open your browser in `resources/public/index.html`. You will not
get live reloading, nor a REPL. 

## License

Copyright © 2017 Tableau

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
