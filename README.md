cs317project2
=============

Partial implementation (code I wrote) of a video player client for my networking class. Some of the method stubs and the implementation of startRTPTimer() was provided by the instructors. I implemented the rest.

Part A of this project involved getting the client to actually play the video data from a server (which was given to us).

Methods implmented for this part include: RTSPConnection(), setup(), play(), pause(), teardown(), recieveRTPPacket(), closeConnection() and parseRTPPacket()


Part B of this project involved dealing with faulty servers (also provided to us) that sent frames out of order, or dropped a lot of frames etc.

outofOrder() is used for debugging, it prints things in the eclipse console.

bufferCleanup(), bufferReady() and order() are used to deal with bad data by implementing YouTube like "buffer" which involves pausing the video, waiting for data, and then playing when we have enough data.

Some changes were also made to startRTPTimer() for the servers that sent data at irregular times.
