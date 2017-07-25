# [OpenCompany](https://github.com/open-company) Interaction Service

[![MPL License](http://img.shields.io/badge/license-MPL-blue.svg?style=flat)](https://www.mozilla.org/MPL/2.0/)
[![Build Status](http://img.shields.io/travis/open-company/open-company-interaction.svg?style=flat)](https://travis-ci.org/open-company/open-company-interaction)
[![Dependency Status](https://www.versioneye.com/user/projects/592ac555a8a0560033ef3675/badge.svg?style=flat)](https://www.versioneye.com/user/projects/592ac555a8a0560033ef3675)
[![Roadmap on Trello](http://img.shields.io/badge/roadmap-trello-blue.svg?style=flat)](https://trello.com/b/3naVWHgZ/open-company-development)


## Background

> Tell the truth about any situation and you are delivered from lack of progress, but become hypocritical or lying, and you may be in bondage for life.

> -- [Auliq Ice](https://www.linkedin.com/in/auliqice/)

Companies struggle to keep everyone on the same page. People are hyper-connected in the moment but still don’t know what’s happening across the company. Employees and investors, co-founders and execs, customers and community, they all want more transparency. The solution is surprisingly simple and effective - great company updates that build transparency and alignment.

With that in mind we designed the [Carrot](https://carrot.io/) software-as-a-service application, powered by the open source [OpenCompany platform](https://github.com/open-company). The product design is based on three principles:

1. It has to be easy or no one will play.
2. The "big picture" should always be visible.
3. Alignment is valuable beyond the team, too.

Carrot simplifies how key business information is shared with stakeholders to create alignment. When information about growth, finances, ownership and challenges is shared transparently, it inspires trust, new ideas and new levels of stakeholder engagement. Carrot makes it easy for founders to engage with employees and investors, creating alignment for everyone.

[Carrot](https://carrot.io/) is GitHub for the rest of your company.

Transparency expectations are changing. Organizations need to change as well if they are going to attract and retain savvy employees and investors. Just as open source changed the way we build software, transparency changes how we build successful companies with information that is open, interactive, and always accessible. Carrot turns transparency into a competitive advantage.

To get started, head to: [Carrot](https://carrot.io/)


## Overview

The OpenCompany Interaction Service handles reading, writing and propagating user comments and reactions.


## Local Setup

Prospective users of [Carrot](https://carrot.io/) should get started by going to [Carrot.io](https://carrot.io/). The following local setup is **for developers** wanting to work on the OpenCompany Interaction Service.

Most of the dependencies are internal, meaning [Leiningen](https://github.com/technomancy/leiningen) will handle getting them for you. There are a few exceptions:

* [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html) - a Java 8 JRE is needed to run Clojure
* [Leiningen](https://github.com/technomancy/leiningen) 2.5.1+ - Clojure's build and dependency management tool
* [ngrok](https://ngrok.com/) - Secure web tunnel to localhost

#### Java

Chances are your system already has Java 8+ installed. You can verify this with:

```console
java -version
```

If you do not have Java 8+ [download it](http://www.oracle.com/technetwork/java/javase/downloads/index.html) and follow the installation instructions.

#### Leiningen

Leiningen is easy to install:

1. Download the latest [lein script from the stable branch](https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein).
1. Place it somewhere that's on your $PATH (`env | grep PATH`). `/usr/local/bin` is a good choice if it is on your PATH.
1. Set it to be executable. `chmod 755 /usr/local/bin/lein`
1. Run it: `lein` This will finish the installation.

Then let Leiningen install the rest of the dependencies:

```console
git clone https://github.com/open-company/open-company-interaction.git
cd open-company-interaction
lein deps
```

#### ngrok

ngrok allows you to setup a secure web tunnel for HTTP/S requests to your localhost. You'll need this
to utilize the Slack webhook during local development so Slack can communicate with your local development
environment.

ngrok is trivial to setup:

1. [Download](https://ngrok.com/download) the version for your operating system.
1. Unzip the download and put ngrok someplace handy for you (in your path is good!)
1. Verify you can run ngrok with: `ngrox help`


## Usage

Run the interaction service with: `lein start`

Or start a REPL with: `lein repl`

#### REPL

Next, you can try some things with Clojure by running the REPL from within this project:

```console
lein migrate-db
lein repl
```

Then enter these commands one-by-one, noting the output:

```clojure
;; start the development system
(go) ; NOTE: if you are already running the service externally to the REPL, use `(go 3737)` to change the port

;; create some interactions

(def author {
  :user-id "c133-43fe-8712"
  :teams ["f725-4791-80ac"]
  :name "Wile E. Coyote"
  :first-name "Wile"
  :last-name "Coyote"
  :avatar-url "http://www.emoticonswallpapers.com/avatar/cartoons/Wiley-Coyote-Dazed.jpg"
  :email "wile.e.coyote@acme.com"
  :auth-source "slack"
})

(interaction/create-comment! conn
  (interaction/->comment {:org-uuid "abcd-1234-abcd"
                          :board-uuid "1234-abcd-1234"
                          :entry-uuid "abcd-5678-abcd"
                          :body "That all looks great to me!"} author))

(interaction/create-reaction! conn
  (interaction/->reaction {:org-uuid "abcd-1234-abcd"
                           :board-uuid "1234-abcd-1234"
                           :entry-uuid "abcd-5678-abcd"
                           :reaction "😀"} author))

(interaction/create-comment-reaction! conn
  (interaction/->comment-reaction {:org-uuid "abcd-1234-abcd"
                                   :board-uuid "1234-abcd-1234"
                                   :entry-uuid "abcd-5678-abcd"
                                   :interaction-uuid "5678-abcd-5678"
                                   :reaction "👌"} author))
```

A Slack webhook is used to mirror Slack replies to OpenCompany comments back into OpenCompany.

To use the webhook from Slack with local development, you need to run ngrok, then configure your Slack integration.

First start the Interaction Service (see above), and start the ngrox tunnel:

```console
ngrok http 3002
```

Note the URL ngrok provides. It will look like: `http://6ae20d9b.ngrok.io` -> localhost:3002

To configure the Slack to use the ngrok tunnel as the destination of message.channel events. Go to
[Your Apps](https://api.slack.com/apps) and click the "OpenCompany (Local Development)" app.

Click the "Event Subscriptions" navigation in the menu. Click the toggle on.

Add the URL provided by ngrok above, modifying `http` to `https` and with a `/slack-event` suffix,
e.g. `https://6ae20d9b.ngrok.io/slack-event`

 Click the "Add Team Event" button and add the `message.channel` event. Click the "Add Bot User Event" button and
 add the `message.channel` event.

Click the "Save Changes" button.

NB: Make sure when you are done testing locally, you disable the "Enable Events" toggle so Slack will stop trying
to echo events to your local environment via ngrok.


## Technical Design

The interaction service is composed of 4 main responsibilites:

- CRUD of comments and reactions
- WebSocket notifications of comment and reaction CRUD to listening clients
- Pushing new comments to Slack
- Receiving new comments from Slack

![Interaction Service Diagram](https://cdn.rawgit.com/open-company/open-company-interaction/mainline/docs/Interaction-Service.svg)

The Interaction Service shares a RethinkDB database instance with the [Storage Service](https://github.com/open-company/open-company-api).

![Interaction Schema Diagram](https://cdn.rawgit.com/open-company/open-company-interaction/mainline/docs/Interaction-Schema.svg)

## Testing

Tests are run in continuous integration of the `master` and `mainline` branches on [Travis CI](https://travis-ci.org/open-company/open-company-interaction):

[![Build Status](https://travis-ci.org/open-company/open-company-interaction.svg?branch=master)](https://travis-ci.org/open-company/open-company-interaction)

To run the tests locally:

```console
lein kibit
lean eastwood
```


## Participation

Please note that this project is released with a [Contributor Code of Conduct](https://github.com/open-company/open-company-interaction/blob/mainline/CODE-OF-CONDUCT.md). By participating in this project you agree to abide by its terms.


## License

Distributed under the [Mozilla Public License v2.0](http://www.mozilla.org/MPL/2.0/).

Copyright © 2015-2017 OpenCompany, LLC.
