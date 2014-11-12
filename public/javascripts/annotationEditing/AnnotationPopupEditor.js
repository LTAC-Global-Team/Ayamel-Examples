/**
 * Created with IntelliJ IDEA.
 * User: josh
 * Date: 7/10/13
 * Time: 2:41 PM
 * To change this template use File | Settings | File Templates.
 */
var AnnotationPopupEditor = (function(){
	"use strict";
    function AnnotationPopupEditor(callback) {
        var annotation = null,
			content = null,
			listeners = {},
			currAnn, // variable to keep track of what word we are working on, Otherwise we wouldn't know which word to annotate
			ractive, editor;

        function emit(event, data) {
            if (listeners[event]) {
                data = data || {};
                listeners[event].forEach(function(callback) {
                    callback(data);
                });
            }
        }

        ractive = new Ractive({
			el: document.body,
			append: true,
			template: '<div id="popupBackground" style="width:100%;height:100%;display:{{hide?"none":"block"}}">\
				<div id="popupEditor">\
					<div id="popupContent">\
						{{#showWord}}\
						<div class="form-inline">\
							<label for="word">Word(s): </label>\
							<input type="text" id="word" placeholder="Word" name="word" value="{{word}}">\
						</div>\
						{{/showWord}}\
						<div class="form-inline">\
							<label for="annotationType">Type: </label>\
							<select id="annotationType" value="{{type}}">\
								<option value="text">Text</option>\
								<option value="image">Image</option>\
								<option value="content">Content</option>\
							</select>\
						</div>\
						{{#(type === "text")}}\
						<div id="textContent">\
							<label for="textEditor">Text:</label>\
							<textarea id="textEditor" data-id="editor"></textarea>\
						</div>\
						{{/text}}\
						{{#(type === "image")}}\
						<div id="imageContent">\
							<div class="form-inline">\
								<label for="url">URL: </label>\
								<input type="text" id="url" placeholder="http://..." value={{imageImg}}>\
							</div>\
							<div class="popupImage" style="background-image:url(\'{{imageImg}}\');"></div>\
						</div>\
						{{/image}}\
						{{#(type === "content")}}\
						<div id="contentContent">\
							<div class="pad-bottom-med">Content:</div>\
							<h4>{{title}}</h4>\
							<div class="popupImage" style="background-image:url(\'{{contentImg}}\');"></div>\
							<button class="btn btn-yellow pad-left-med" on-tap="browse"><i class="icon-folder-open"></i> Select Content</button>\
						</div>\
						{{/content}}\
					</div>\
					<div>\
						<div class="pull-left">\
							<button class="btn btn-magenta" on-tap=	"delete" tmpl-attach="delete"><i class="icon-trash"></i></button>\
						</div>\
						<div class="pull-right">\
							<button class="btn" on-tap="cancel"><i class="icon-ban-circle"></i></button>\
							<button class="btn btn-blue" on-tap="save"><i class="icon-save"></i></button>\
						</div>\
					</div>\
				</div>\
			</div>',
			data: {
				type: "text",
				hide: true,
				showWord: true
			}
		});
		editor = ractive.find('[data-id="editor"]');
		ractive.on('cancel',function() {
			ractive.set('hide', true);
		});
		ractive.on('save',function() {
			ractive.set('hide', true);
			emit("update");
		});
		ractive.on('delete',function() {
			ractive.set('hide', true);
			emit("delete");
		});
		ractive.on('browse',function(){
			PopupBrowser.selectContent(function(newContent){
				ContentCache.cache[newContent.id] = newContent;
				content = newContent;
				ractive.set({
					contentImg: ContentThumbnails.resolve(content),
					title: content.name
				});
			});
		});

		// Setup the WYSIWYG editor
		$(editor).wysihtml5({
			"stylesheets": ["/assets/wysihtml5/lib/css/wysiwyg-color.css"], // CSS stylesheets to load
			"color": true, // enable text color selection
			"size": 'small', // buttons size
			"html": true // enable button to edit HTML
		});

		/*
		 * Update functions
		 */

		function updateAnnotation() {
			//annotation.annotations[annSize].regex = new RegExp(ractive.get('word'));
			annotation[currAnn]["global"]["data"]["type"] = ractive.get('type');

			// Check the data type
			switch(annotation[currAnn]["global"]["data"]["type"]){
			case "text": // Update from the text editor
				annotation[currAnn]["global"]["data"]["value"] = $('#textEditor').data("wysihtml5").editor.getValue();
				break;
			case "image": // Update from the URL text input
				annotation[currAnn]["global"]["data"]["value"] = ractive.get('imageImg');
				break;
			case "content": // Update from the selected content
				annotation[currAnn]["global"]["data"]["value"] = !!content ? content.id : 0;
				break;
			}
		}

		function updateForm() {
			// This is my implementation
			if (annotation instanceof ImageAnnotation) {
				// Hide the word editor
				ractive.set('showWord', false);
			}else {
				// Load the annotation data into the form
				ractive.set({
					showWord: true,
					word: currAnn
				});
			}
			
			content = null;

			// Check the data type
			switch(annotation[currAnn]["global"]["data"]["type"]){
			case "text": // Update the text editor
				$('#textEditor').data("wysihtml5").editor.setValue(annotation[currAnn]["global"]["data"]["value"]);
				$('#textEditor').data("wysihtml5").editor.focus();

				ractive.set({
					type: "text",
					imageImg: "",
					contentImg: ""
				});
				break;
			case "image": // Update the URL text input
				editor.setValue("");
				ractive.set({
					type: "image",
					imageImg: annotation.data.value,
					contentImg: ""
				});
				break;
			case "content":	// Load the content
				editor.setValue("");
				ractive.set({
					type: "content",
					imageImg: "",
					contentImg: ""
				});
				ContentCache.load(annotation.data.value, function(newContent) {
					content = newContent;
					ractive.set({
						contentImg: ContentThumbnails.resolve(content),
						title: content.name
					});
				});
				break;
			default:
				throw new Error("Unrecognized annotation data.");
			}
		}

		Object.defineProperties(this, {
			show: {
				value: function(){ ractive.set('hide', false); }
			},
			annotation: {
				get: function() {
					updateAnnotation();
					return annotation;
				},
				set: function(value) {
					annotation = value["manifest"];
					currAnn = value["word"];
					updateForm();
				}
			},
			on: {
				value: function(event, callback) {
					if (listeners[event] instanceof Array) {
						listeners[event].push(callback);
					} else {
						listeners[event] = [callback];
					}
				}
			}
		});

        if(typeof callback === 'function'){ callback(this); }
    }

    return AnnotationPopupEditor;
})();