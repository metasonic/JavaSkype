package fr.delthas.skype;

import org.json.JSONArray;
import org.json.JSONObject;

public class HeroCard {
    /*
    You can create more cards using https://adaptivecards.io/
     */

    public JSONObject createHeroCard(String title, String subtitle, String cardText, JSONArray arrayOfButtons) {
        String imageUrl = "https://i.imgur.com/7ea46Xl.gif";

        JSONObject json = new JSONObject();
        json.put("attachments",
                new JSONArray()
                        .put(new JSONObject()
                                .put("contentType", "application/vnd.microsoft.card.hero")
                                .put("content",
                                        new JSONObject().put("images",
                                                new JSONArray().put(new JSONObject().put("url", imageUrl)))
                                                .put("title", title)
                                                .put("subtitle", subtitle)
                                                .put("text", cardText)
                                                .put("buttons", arrayOfButtons))))
                .put("type", "message/card")
                .put("recipient", new JSONObject().put("id", "")
                        .put("name", ""));
        return json;
    }

    public JSONObject createButton(String title, String urlToGoTo) {
        return new JSONObject()
                .put("type", "openUrl")
                .put("title", title)
                .put("value", urlToGoTo);
    }

    /*
    Example of HeroCard
        {
            "attachments": [{
                "contentType": "application/vnd.microsoft.card.hero",
                "content": {
                    "images": [{
                        "url": "{imageUrl}"
                    }],
                    "title": "{title}",
                    "subtitle": "{subtitle}",
                    "text": "{text}",
                    "buttons": [{
                        "type": "openUrl",
                        "title": "{title}",
                        "value": "https://example.com/"
                    }, {
                        "type": "openUrl",
                        "title": "{title}",
                        "value": "https://example.com/"
                    }, {
                        "type": "openUrl",
                        "title": "{title}",
                        "value": "https://example.com/"
                    }]
                }
            }],
            "type": "message/card",
            "recipient": {
                "id": "",
                "name": ""
            }
        }
     */
}
