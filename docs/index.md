---
layout: default
title: "Introduction to Kweb"
---

1. TOC
{:toc}

#### What is Kweb?

Kweb is a library for building rich web applications in the [Kotlin](http://kotlinlang.org/)
programming language.  You can think of it as a powerful Kotlin DSL that allows you to remote-control
web browsers from a web server.

Even though your code runs on the server, Kweb allows you to interact with the browser DOM directly, for example here 
we create a `<p>` element and set its text:

```kotlin
doc.body.p().setText("this is an example HTML paragraph")
```

You can also interact with JavaScript directly, although you should rarely need to do this:

```kotlin
doc.body.execute("console.log((new Date()).getDay());")
```

You can even query JavaScript and do something with the result:

```kotlin
val day = doc.body.evaluate("$('#day').text()")
database.update(BOOK).set(BOOK.DAY, day).execute()
```

But Kweb's real power is what's built on top of this low-level framework.  Through its plugin mechanism
Kotlin lets you use powerful JavaScript libraries like [Material Design Lite](https://getmdl.io/) through
a Kotlin DSL that mirrors the library's API, but with the benefits of Kotlin's DSL-friendly syntax and
its type safety:

```kotlin
header().apply {
  row().apply {
     title().setText("Title")
     spacer()
     navigation().apply {
       navLink().setText("Delete").on.click {
            database.delete().where(ID.eq(oId)).execute()
       }
       navLink().setText("Create")
       navLink().setText("Modify")
     }
   }
}
```

Aside from Material, Kotlin has plugins for JavaScript libraries like [JQuery](https://jquery.com/),
[select2](https://select2.github.io/), and [others](https://github.com/sanity/kweb/tree/master/src/main/kotlin/com/github/sanity/kweb/plugins).
It's also surprisingly easy to build your own plugins for other JavaScript libraries, or extend those Kweb already
supports.

#### Features
* Build websites in Kotlin
* Interact with the user just like powerful but complex JavaScript frameworks
  like React or Angular
* Makes the barrier between web-browser and web-server largely invisible
* Seamlessly integrates with powerful JavaScript libraries like JQuery, MDL, and Bootstrap
* Update your web browser instantly in response to code changes
* Easy to add to an existing project, Kweb is just a library, it doesn't seek to tell you how your project should
  be organized
  
#### How does it work?
Kweb keeps all of the logic server-side, and uses efficient websockets to communicate with web 
browsers. We also take advantage of Kotlin's powerful new coroutines mechanism to efficiently handle
asynchronicity, largly invisibly to the programmer.

#### What does it look like?

Here we create a simple "todo" list app:

```kotlin
fun main(args: Array<String>) {
    Kweb(8091, debug = true) {
        doc.body.apply {
            h1().addText("Simple Kweb demo - a to-do list")
            p().addText("Edit the text box below and click the button to add the item.  Click an item to remove it.")

            val ul = ul().apply {
                listOf("one", "two", "three").map { 
                    newListItem(it) 
                }
            }

            val inputElement = input(type = InputType.text, size = 20)

            val button = button()
            button.addText("Add Item")
            button.on.click {
                future {
                    val newItemText = inputElement.getValue().await()
                    ul.newListItem(newItemText)
                    inputElement.setValue("")
                }
            }
        }
    }
    Thread.sleep(10000)
}

fun ULElement.newListItem(text: String) {
    li().apply {
        addText(text)
        on.click { event ->
            delete() }
    }
}
```
**Next: [Setting Up]({{ site.baseurl }}{% post_url 2017-03-03-getting-started %}) >>>>**
