[![License badge](https://img.shields.io/badge/license-Apache2-orange.svg)](http://www.apache.org/licenses/LICENSE-2.0)
[![Documentation badge](https://readthedocs.org/projects/fiware-orion/badge/?version=latest)](http://doc-kurento.readthedocs.org/en/latest/)


Copyright © 2013-2016 [Kurento]. Licensed under [Apache 2.0 License].

Yellow Beam
===================

A cooperative Streaming platform.

Running YellowBeam
---------------------

In order to use Yellow Beam you will need Kurento Media Server installed on the server machine.
You can install it following [this Kurento Media Server Instalation Guide](https://doc-kurento.readthedocs.io/en/6.13.0/user/installation.html).

Now folow this steps to start the Yellow Beam Server:

1. Download the latest Yellow Beam version on your Machine and cd to the download directory:
```
 git clone https://github.com/vigocoffeelovers/YellowBeam.git
 cd YellowBeam
```

2. Ensure that the Kurento MS is currently running:
```
 sudo service kurento-media-server start
```

3. Launch the aplication:
```
mvn -U clean spring-boot:run -Dkms.url=ws://localhost:8888/kurento
```

You can also use -X option to start it on debug mode:
```
 mvn -X -U clean spring-boot:run -Dkms.url=ws://localhost:8888/kurento
```

Now the server is listening to requests on: https://localhost:8443


[Kurento]: http://kurento.org
