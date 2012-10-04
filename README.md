## MPDroid

**MPDroid** is a MPD client for Android. It is a fork of [PMix](http://code.google.com/p/pmix/).

You can browse your library, control the current song and playlist, manage your outputs, and stream music right to your phone. And all of this wrapped up in a beautiful modern Holo design !

What's not to like ? 

![Now Playing/library Screenshot](https://raw.github.com/abarisain/dmix/master/Screenshots/readme.png)

#### Compatibility

MPDroid works on **all** devices from 2.2 to 4.1
1280x800 (13") Tablets are also supported, and even have a special layout.
10" will use the phone layout, and Nexus 7 support is being worked on.


#### Libraries used

ActionBarSherlock - Wonderful library, allowing me to backport all of the holo goodness to Android 2.x  
JmDNS - For bonjour integration. Still WIP (and may even be finished one day), allows local MPD servers to be discovered without finding their IP  
JMPDComm - The core MPD interface lib. Heavily modified.
LastFM-java - Last.FM cover art support

## Known issues
 - Limited multi server support (based on Wlan name)
 - Sometimes drops connection
 - Lack some library information (read the roadmap)

## Roadmap
New features will most likely be delayed. MPDroid is, in my opinion, almost fully-featured and pushes (hits) MPD's api limits. Widely requested features (like better search, better library browsing) can't be done whithout duplicating MPD's database locally.  
This is a huge project, and it will take a lot of time (if ever done).
Also, MPDroid's speed isn't that great, but considering that MPD's api was never made for 3G (read crappy) connections, it is also not that bad.

So, the current roadmap is :

####1.0
 - Fix the annoying bugs
 - Make the connection to MPD robust, getting rid of the horrible connection errors
 - Release a new market version
 - Release a legacy market versions, because some people will want it back

####1.1
 - Add lyrics & artist/album info
 - Make MPDroid configurable for multiple servers (Better implementation than the hackish wlan based one)
 - Add bonjour support
 - Keep it the most up to date and best MPDroid client for android
 - ???
 - Profit

## Special thanks

Obviously, PMix for their work on the original application. There is not much of PMix left, but their core code is still here.

Everybody who blogged about MPDroid, allowing it to gain a large userbase.
Mopidy, for working with me on the search support.

#### Developpers

There are a lot of people here. Developpers who crossed my path now and then, working on mpdroid and fixing some things. This project wouldn't be the same without you !

[Kent Gustavsson](https://github.com/orrche) - Cleaned up a lot of PMix's old bugged core and polished the interface. Huge refactoring work on the activities.

[Phil Chandler](https://github.com/philchand) - Various fixes and improvements to internal stuff. Added more options to the way it handles the library.

[Guillaume Revaillot](https://github.com/grevaillot) - Tablet layout work

Andrew Blackburn - Various stuff, suggestions and layout work

[Stefan Richter](https://github.com/02strich) - Refactoring and … Widget ! I was really too lazy about this one, and it was a much requested feature. Also added a way to build MPDroid from the command line, which I didn't even try.

[Matt Black](https://github.com/mafrosis) - Also worked on ant building. Reported a log of bugs/improvement suggestions. We may not agree on everything but it's been useful !

Craig Drummond - Helped me integrate new features while cleaning up old internal code that I did not really wanted to change. Lots of new interface code and feedback on my experimentations while reworking the whole interface.

Other patch submitters : Jörg Thalheim, Florian Weile, Daniel Schoepe, John Bäckstrand, ...

And if I forgot you … I'm sorry, it's kinda hard to remember everybody who worked on a 2 year old project, feel free to mail me :)


Thanks a lot guys!