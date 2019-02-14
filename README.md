# FastPacketListener

## How to use
````java
        // Create a new packet listener for the plugin
        FastPacketListener packetListener = new FastPacketListener(this);

        // Add a packet handler
        packetListener.addHandler((player, channel, packet, direction) -> {            

            if (something) {
                // When 'false' is returned the packet is cancelled
                return false;
            }
            
            return true; // The packet is not cancelled
        });
````

## TODO
* Finish README
* Add JavaDoc
* Deploy to an other maven repo
